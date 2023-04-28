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
package org.dependencytrack.event;

import alpine.event.framework.AbstractChainableEvent;
import alpine.event.framework.Event;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.Project;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines an {@link Event} used to trigger policy evaluation.
 */
public class PolicyEvaluationEvent extends AbstractChainableEvent {

    private List<Component> components = new ArrayList<>();
    private Project project;

    /**
     * Default constructed used to signal that a portfolio analysis
     * should be performed on all components.
     */
    public PolicyEvaluationEvent() {

    }

    /**
     * Creates an event to analyze the specified components.
     * @param components the components to analyze
     */
    public PolicyEvaluationEvent(final List<Component> components) {
        this.components = components;
    }

    /**
     * Creates an event to analyze the specified component.
     * @param component the component to analyze
     */
    public PolicyEvaluationEvent(final Component component) {
        this.components.add(component);
    }

    /**
     * Returns the list of components to analyze.
     * @return the list of components to analyze
     */
    public List<Component> getComponents() {
        return this.components;
    }

    /**
     * Fluent method that sets the project these components are
     * optionally a part of and returns this instance.
     */
    public PolicyEvaluationEvent project(final Project project) {
        this.project = project;
        return this;
    }

    /**
     * Returns the project these components are optionally a part of.
     */
    public Project getProject() {
        return project;
    }

}
