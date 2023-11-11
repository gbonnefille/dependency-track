/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.tasks;

import alpine.common.logging.Logger;
import alpine.event.framework.Event;
import alpine.event.framework.LoggableUncaughtExceptionHandler;
import alpine.event.framework.Subscriber;
import alpine.model.ConfigProperty;
import io.github.jeremylong.openvulnerability.client.nvd.CveItem;
import io.github.jeremylong.openvulnerability.client.nvd.DefCveItem;
import io.github.jeremylong.openvulnerability.client.nvd.NvdCveClient;
import io.github.jeremylong.openvulnerability.client.nvd.NvdCveClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.datanucleus.flush.FlushMode;
import org.dependencytrack.event.IndexEvent;
import org.dependencytrack.event.IndexEvent.Action;
import org.dependencytrack.event.NistMirrorEvent;
import org.dependencytrack.model.AffectedVersionAttribution;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.Vulnerability.Source;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.persistence.QueryManager;
import org.dependencytrack.util.PersistenceUtil.Diff;
import org.dependencytrack.util.PersistenceUtil.Differ;

import javax.jdo.Query;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.github.jeremylong.openvulnerability.client.nvd.NvdCveClientBuilder.aNvdCveApi;
import static org.datanucleus.PropertyNames.PROPERTY_FLUSH_MODE;
import static org.datanucleus.PropertyNames.PROPERTY_PERSISTENCE_BY_REACHABILITY_AT_COMMIT;
import static org.dependencytrack.model.ConfigPropertyConstants.VULNERABILITY_SOURCE_NVD_API_KEY;
import static org.dependencytrack.model.ConfigPropertyConstants.VULNERABILITY_SOURCE_NVD_API_LAST_MODIFIED_EPOCH_SECONDS;
import static org.dependencytrack.model.ConfigPropertyConstants.VULNERABILITY_SOURCE_NVD_API_URL;
import static org.dependencytrack.parser.nvd.api20.ModelConverter.convert;
import static org.dependencytrack.parser.nvd.api20.ModelConverter.convertConfigurations;
import static org.dependencytrack.util.PersistenceUtil.assertPersistent;

/**
 * A {@link Subscriber} that mirrors the content of the NVD through the NVD API 2.0.
 *
 * @since 4.10.0
 */
public class NistApiMirrorTask implements Subscriber {

    private static final Logger LOGGER = Logger.getLogger(NistApiMirrorTask.class);

    public NistApiMirrorTask() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inform(final Event e) {
        if (!(e instanceof NistMirrorEvent)) {
            return;
        }

        final String apiUrl, apiKey;
        final long lastModifiedEpochSeconds;
        try (final var qm = new QueryManager()) {
            final ConfigProperty apiUrlProperty = qm.getConfigProperty(
                    VULNERABILITY_SOURCE_NVD_API_URL.getGroupName(),
                    VULNERABILITY_SOURCE_NVD_API_URL.getPropertyName()
            );
            final ConfigProperty apiKeyProperty = qm.getConfigProperty(
                    VULNERABILITY_SOURCE_NVD_API_KEY.getGroupName(),
                    VULNERABILITY_SOURCE_NVD_API_KEY.getPropertyName()
            );
            final ConfigProperty lastModifiedProperty = qm.getConfigProperty(
                    VULNERABILITY_SOURCE_NVD_API_LAST_MODIFIED_EPOCH_SECONDS.getGroupName(),
                    VULNERABILITY_SOURCE_NVD_API_LAST_MODIFIED_EPOCH_SECONDS.getPropertyName()
            );

            apiUrl = Optional.ofNullable(apiUrlProperty)
                    .map(ConfigProperty::getPropertyValue)
                    .map(StringUtils::trimToNull)
                    .orElseThrow(() -> new IllegalStateException("No API URL configured"));
            apiKey = Optional.ofNullable(apiKeyProperty)
                    .map(ConfigProperty::getPropertyValue)
                    .map(StringUtils::trimToNull)
                    .map(encryptedApiKey -> {
                        try {
                            // TODO: Skipping decryption for easier local testing. Add this back in.
                            // DataEncryption.decryptAsString(encryptedApiKey);
                            return encryptedApiKey;
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to decrypt API key; Continuing without authentication", ex);
                            return null;
                        }
                    })
                    .orElse(null);
            lastModifiedEpochSeconds = Optional.ofNullable(lastModifiedProperty)
                    .map(ConfigProperty::getPropertyValue)
                    .map(StringUtils::trimToNull)
                    .filter(StringUtils::isNumeric)
                    .map(Long::parseLong)
                    .orElse(0L);
        }

        // NvdCveClient queues Futures for all to-be-fetched pages of the NVD API upfront.
        // Each future will perform an HTTP request, and provide the HTTP response as result.
        // Responses are only consumed and parsed by the client when NvdCveClient#next is called.
        // Responses are pretty large as each page contains up to 2000 CVEs in JSON format.
        // If invocations of #next are too infrequent, unconsumed responses will pile up in memory.
        //
        // In an attempt to prevent unconsumed responses from staying around for too long,
        // we utilize this task's thread solely for converting them to our internal object model.
        // Actual synchronization with the database is offloaded to a separate executor thread.
        // This way, response objects can be GC'd quicker, significantly reducing memory footprint.
        final BasicThreadFactory factory = new BasicThreadFactory.Builder()
                .namingPattern(getClass().getSimpleName() + "-%d")
                .uncaughtExceptionHandler(new LoggableUncaughtExceptionHandler())
                .build();

        final long startTimeNs = System.nanoTime();
        ZonedDateTime lastModified;
        try (final NvdCveClient client = createApiClient(apiUrl, apiKey, lastModifiedEpochSeconds);
             final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory)) {
            while (client.hasNext()) {
                for (final DefCveItem defCveItem : client.next()) {
                    final CveItem cveItem = defCveItem.getCve();
                    if (cveItem == null) {
                        continue;
                    }

                    final Vulnerability vuln = convert(cveItem);
                    final List<VulnerableSoftware> vsList = convertConfigurations(cveItem.getId(), cveItem.getConfigurations());

                    executor.submit(() -> {
                        try (final var qm = new QueryManager().withL2CacheDisabled()) {
                            // Delay flushes to the datastore until commit.
                            qm.getPersistenceManager().setProperty(PROPERTY_FLUSH_MODE, FlushMode.MANUAL.name());
                            qm.getPersistenceManager().setProperty(PROPERTY_PERSISTENCE_BY_REACHABILITY_AT_COMMIT, "false");

                            // Note: persistentVuln is in HOLLOW state (all fields except ID are unloaded).
                            // https://www.datanucleus.org/products/accessplatform_6_0/jdo/persistence.html#lifecycle
                            final Vulnerability persistentVuln = synchronizeVulnerability(qm, vuln);
                            synchronizeVulnerableSoftware(qm, persistentVuln, vsList);
                        }
                    });
                }
            }

            lastModified = client.getLastUpdated();
        } catch (Exception ex) {
            LOGGER.error("An unexpected error occurred while mirroring the contents of the National Vulnerability Database", ex);
            return;
        } finally {
            LOGGER.info("Mirroring completed in %s".formatted(Duration.ofNanos(System.nanoTime() - startTimeNs)));
        }

        if (updateLastModified(lastModified)) {
            Event.dispatch(new IndexEvent(Action.COMMIT, Vulnerability.class));
        }
    }

    private static Vulnerability synchronizeVulnerability(final QueryManager qm, final Vulnerability vuln) {
        final Pair<Vulnerability, IndexEvent> vulnIndexEventPair = qm.runInTransaction(trx -> {
            trx.setSerializeRead(true); // SELECT ... FOR UPDATE

            Vulnerability persistentVuln = getVulnerabilityByCveId(qm, vuln.getVulnId());
            if (persistentVuln == null) {
                persistentVuln = qm.getPersistenceManager().makePersistent(vuln);
                return Pair.of(persistentVuln, new IndexEvent(Action.CREATE, persistentVuln));
            } else {
                final Map<String, Diff> diffs = updateVulnerability(persistentVuln, vuln);
                if (!diffs.isEmpty()) {
                    LOGGER.debug("%s has changed: %s".formatted(vuln.getVulnId(), diffs));
                    return Pair.of(persistentVuln, new IndexEvent(Action.UPDATE, persistentVuln));
                }

                LOGGER.debug("%s has not changed".formatted(vuln.getVulnId()));
                return Pair.of(persistentVuln, null);
            }
        });

        final IndexEvent indexEvent = vulnIndexEventPair.getRight();
        final Vulnerability persistentVuln = vulnIndexEventPair.getLeft();

        if (indexEvent != null) {
            Event.dispatch(indexEvent);
        }

        return persistentVuln;
    }

    private static void synchronizeVulnerableSoftware(final QueryManager qm, final Vulnerability persistentVuln, final List<VulnerableSoftware> vsList) {
        qm.runInTransaction(tx -> {
            tx.setSerializeRead(false);

            // Get all `VulnerableSoftware`s that are currently associated with the vulnerability.
            final List<VulnerableSoftware> oldVsList = qm.getVulnerableSoftwareByVulnId(Source.NVD.name(), persistentVuln.getVulnId());
            LOGGER.trace("%s: Existing VS: %d".formatted(persistentVuln.getVulnId(), oldVsList.size()));

            for (final VulnerableSoftware vsOld : oldVsList) {
                vsOld.setAffectedVersionAttributions(qm.getAffectedVersionAttributions(persistentVuln, vsOld));
            }

            // Based on the lists of currently reported, and previously reported `VulnerableSoftware`s,
            // divide the previously reported ones into lists of items to keep, and items to remove.
            // Remaining items in vsList are entirely new.
            final var vsListToRemove = new ArrayList<VulnerableSoftware>();
            final var vsListToKeep = new ArrayList<VulnerableSoftware>();
            for (final VulnerableSoftware oldVs : oldVsList) {
                if (vsList.removeIf(oldVs::equalsIgnoringDatastoreIdentity)) {
                    vsListToKeep.add(oldVs);
                } else {
                    final List<AffectedVersionAttribution> attributions = oldVs.getAffectedVersionAttributions();
                    if (attributions == null) {
                        // DT versions prior to 4.7.0 did not record attributions.
                        // Drop the VulnerableSoftware for now. If it was previously
                        // reported by another source, it will be recorded and attributed
                        // whenever that source is mirrored again.
                        vsListToRemove.add(oldVs);
                        continue;
                    }

                    final boolean previouslyReportedBySource = attributions.stream()
                            .anyMatch(attr -> attr.getSource() == Source.NVD);
                    final boolean previouslyReportedByOthers = attributions.stream()
                            .anyMatch(attr -> attr.getSource() != Source.NVD);

                    if (previouslyReportedByOthers) {
                        // Reported by another source, keep it.
                        vsListToKeep.add(oldVs);
                    } else if (previouslyReportedBySource) {
                        // Not reported anymore, remove attribution.
                        vsListToRemove.add(oldVs);
                    } else {
                        // Should never happen, but better safe than sorry.
                        vsListToRemove.add(oldVs);
                    }
                }
            }
            LOGGER.trace("%s: vsListToKeep: %d".formatted(persistentVuln.getVulnId(), vsListToKeep.size()));
            LOGGER.trace("%s: vsListToRemove: %d".formatted(persistentVuln.getVulnId(), vsListToRemove.size()));

            // Remove attributions from `VulnerableSoftware`s that are no longer reported.
            if (!vsListToRemove.isEmpty()) {
                qm.deleteAffectedVersionAttributions(persistentVuln, vsListToRemove, Source.NVD);
            }

            final var attributionDate = new Date();

            // For `VulnerableSoftware`s that existed before, update the lastSeen timestamp.
            for (final VulnerableSoftware oldVs : vsListToKeep) {
                oldVs.getAffectedVersionAttributions().stream()
                        .filter(attribution -> attribution.getSource() == Source.NVD)
                        .findAny()
                        .ifPresent(attribution -> attribution.setLastSeen(attributionDate));
            }

            // For `VulnerableSoftware`s that are newly reported for this `Vulnerability`, check if any matching
            // records exist in the database that are currently associated with other `Vulnerability`s.
            for (final VulnerableSoftware vs : vsList) {
                final VulnerableSoftware existingVs = qm.getVulnerableSoftwareByCpe23(
                        vs.getCpe23(),
                        vs.getVersionEndExcluding(),
                        vs.getVersionEndIncluding(),
                        vs.getVersionStartExcluding(),
                        vs.getVersionStartIncluding()
                );
                if (existingVs != null) {
                    // final boolean hasAttribution = qm.hasAffectedVersionAttribution(persistentVuln, vs, Source.NVD);
                    if (true) {
                        LOGGER.trace("%s: Adding attribution".formatted(persistentVuln.getVulnId()));
                        final AffectedVersionAttribution attribution = createAttribution(persistentVuln, existingVs, attributionDate);
                        qm.getPersistenceManager().makePersistent(attribution);
                    }
                    vsListToKeep.add(existingVs);
                } else {
                    LOGGER.trace("%s: Creating new VS".formatted(persistentVuln.getVulnId()));
                    final VulnerableSoftware persistentVs = qm.getPersistenceManager().makePersistent(vs);
                    final AffectedVersionAttribution attribution = createAttribution(persistentVuln, persistentVs, attributionDate);
                    qm.getPersistenceManager().makePersistent(attribution);
                    vsListToKeep.add(persistentVs);
                }
            }

            LOGGER.trace("%s: Final vsList: %d".formatted(persistentVuln.getVulnId(), vsListToKeep.size()));
            persistentVuln.setVulnerableSoftware(vsListToKeep);
        });
    }

    private static NvdCveClient createApiClient(final String apiUrl, final String apiKey, final long lastModifiedEpochSeconds) {
        final NvdCveClientBuilder clientBuilder = aNvdCveApi().withEndpoint(apiUrl);
        if (apiKey != null) {
            clientBuilder.withApiKey(apiKey);
        } else {
            LOGGER.warn("No API key configured; Aggressive rate limiting to be expected");
        }
        if (lastModifiedEpochSeconds > 0) {
            final var start = ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneOffset.UTC);
            clientBuilder.withLastModifiedFilter(start, start.minusDays(-120));
            LOGGER.info("Mirroring CVEs that were modified since %s".formatted(start));
        } else {
            LOGGER.info("Mirroring CVEs that were modified since %s"
                    .formatted(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)));
        }

        return clientBuilder.build();
    }

    private static boolean updateLastModified(final ZonedDateTime lastModifiedDateTime) {
        if (lastModifiedDateTime == null) {
            LOGGER.debug("Encountered no modified CVEs");
            return false;
        }

        LOGGER.debug("Latest captured modification date: %s".formatted(lastModifiedDateTime));
        try (final var qm = new QueryManager().withL2CacheDisabled()) {
            qm.runInTransaction(() -> {
                final ConfigProperty property = qm.getConfigProperty(
                        VULNERABILITY_SOURCE_NVD_API_LAST_MODIFIED_EPOCH_SECONDS.getGroupName(),
                        VULNERABILITY_SOURCE_NVD_API_LAST_MODIFIED_EPOCH_SECONDS.getPropertyName()
                );

                property.setPropertyValue(String.valueOf(lastModifiedDateTime.toEpochSecond()));
            });
        }

        return true;
    }

    private static AffectedVersionAttribution createAttribution(final Vulnerability vuln, final VulnerableSoftware vs,
                                                                final Date attributionDate) {
        final var attribution = new AffectedVersionAttribution();
        attribution.setSource(Source.NVD);
        attribution.setVulnerability(vuln);
        attribution.setVulnerableSoftware(vs);
        attribution.setFirstSeen(attributionDate);
        attribution.setLastSeen(attributionDate);
        return attribution;
    }

    /**
     * Get a {@link Vulnerability} by its CVE ID (implying the source {@link Source#NVD}).
     * <p>
     * It differs from {@link QueryManager#getVulnerabilityByVulnId(String, String)} in that it does not fetch any
     * adjacent relationships (e.g. affected components and aliases).
     *
     * @param qm    The {@link QueryManager} to use
     * @param cveId The CVE ID to look for
     * @return The {@link Vulnerability} matching the CVE ID, or {@code null} when no match was found
     */
    private static Vulnerability getVulnerabilityByCveId(final QueryManager qm, final String cveId) {
        final Query<Vulnerability> query = qm.getPersistenceManager().newQuery(Vulnerability.class);
        query.setFilter("source == :source && cveId == :cveId");
        query.setNamedParameters(Map.of(
                "source", Source.NVD.name(),
                "cveId", cveId
        ));
        try {
            return query.executeUnique();
        } finally {
            query.closeAll();
        }
    }

    /**
     * Update an existing, persistent {@link Vulnerability} with data as reported by the NVD.
     * <p>
     * It differs from {@link QueryManager#updateVulnerability(Vulnerability, boolean)} in that it keeps track of
     * which fields are modified, and assumes the to-be-updated {@link Vulnerability} to be persistent, and enrolled
     * in an active {@link javax.jdo.Transaction}.
     *
     * @param existingVuln The existing {@link Vulnerability} to update
     * @param reportedVuln The {@link Vulnerability} as reported by the NVD
     * @return A {@link Map} holding the differences of all updated fields
     */
    private static Map<String, Diff> updateVulnerability(final Vulnerability existingVuln, final Vulnerability reportedVuln) {
        assertPersistent(existingVuln, "existingVuln must be persistent in order for changes to be effective");

        final var differ = new Differ<>(existingVuln, reportedVuln);
        differ.applyIfChanged("title", Vulnerability::getTitle, existingVuln::setTitle);
        differ.applyIfChanged("subTitle", Vulnerability::getSubTitle, existingVuln::setSubTitle);
        differ.applyIfChanged("description", Vulnerability::getDescription, existingVuln::setDescription);
        differ.applyIfChanged("detail", Vulnerability::getDetail, existingVuln::setDetail);
        differ.applyIfChanged("recommendation", Vulnerability::getRecommendation, existingVuln::setRecommendation);
        differ.applyIfChanged("references", Vulnerability::getReferences, existingVuln::setReferences);
        differ.applyIfChanged("credits", Vulnerability::getCredits, existingVuln::setCredits);
        differ.applyIfChanged("created", Vulnerability::getCreated, existingVuln::setCreated);
        differ.applyIfChanged("published", Vulnerability::getPublished, existingVuln::setPublished);
        differ.applyIfChanged("updated", Vulnerability::getUpdated, existingVuln::setUpdated);
        differ.applyIfChanged("cwes", Vulnerability::getCwes, existingVuln::setCwes);
        // Calling setSeverity nulls all CVSS and OWASP RR fields. getSeverity calculates the severity on-the-fly,
        // and will return UNASSIGNED even when no severity is set explicitly. Thus, calling setSeverity
        // must happen before CVSS and OWASP RR fields are set, to avoid null-ing them again.
        differ.applyIfChanged("severity", Vulnerability::getSeverity, existingVuln::setSeverity);
        differ.applyIfChanged("cvssV2BaseScore", Vulnerability::getCvssV2BaseScore, existingVuln::setCvssV2BaseScore);
        differ.applyIfChanged("cvssV2ImpactSubScore", Vulnerability::getCvssV2ImpactSubScore, existingVuln::setCvssV2ImpactSubScore);
        differ.applyIfChanged("cvssV2ExploitabilitySubScore", Vulnerability::getCvssV2ExploitabilitySubScore, existingVuln::setCvssV2ExploitabilitySubScore);
        differ.applyIfChanged("cvssV2Vector", Vulnerability::getCvssV2Vector, existingVuln::setCvssV2Vector);
        differ.applyIfChanged("cvssV3BaseScore", Vulnerability::getCvssV3BaseScore, existingVuln::setCvssV3BaseScore);
        differ.applyIfChanged("cvssV3ImpactSubScore", Vulnerability::getCvssV3ImpactSubScore, existingVuln::setCvssV3ImpactSubScore);
        differ.applyIfChanged("cvssV3ExploitabilitySubScore", Vulnerability::getCvssV3ExploitabilitySubScore, existingVuln::setCvssV3ExploitabilitySubScore);
        differ.applyIfChanged("cvssV3Vector", Vulnerability::getCvssV3Vector, existingVuln::setCvssV3Vector);
        differ.applyIfChanged("owaspRRLikelihoodScore", Vulnerability::getOwaspRRLikelihoodScore, existingVuln::setOwaspRRLikelihoodScore);
        differ.applyIfChanged("owaspRRTechnicalImpactScore", Vulnerability::getOwaspRRTechnicalImpactScore, existingVuln::setOwaspRRTechnicalImpactScore);
        differ.applyIfChanged("owaspRRBusinessImpactScore", Vulnerability::getOwaspRRBusinessImpactScore, existingVuln::setOwaspRRBusinessImpactScore);
        differ.applyIfChanged("owaspRRVector", Vulnerability::getOwaspRRVector, existingVuln::setOwaspRRVector);
        differ.applyIfChanged("vulnerableVersions", Vulnerability::getVulnerableVersions, existingVuln::setVulnerableVersions);
        differ.applyIfChanged("patchedVersions", Vulnerability::getPatchedVersions, existingVuln::setPatchedVersions);
        // EPSS is an additional enrichment that no source currently provides natively. We don't want EPSS scores of CVEs to be purged.
        differ.applyIfNonNullAndChanged("epssScore", Vulnerability::getEpssScore, existingVuln::setEpssScore);
        differ.applyIfNonNullAndChanged("epssPercentile", Vulnerability::getEpssPercentile, existingVuln::setEpssPercentile);

        return differ.getDiffs();
    }

}
