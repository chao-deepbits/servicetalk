/*
 * Copyright © 2023 Apple Inc. and the ServiceTalk project authors
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
 */
package io.servicetalk.loadbalancer;

import io.servicetalk.client.api.LoadBalancerFactory;
import io.servicetalk.concurrent.api.Executor;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

final class RoundRobinLoadBalancerBuilderAdapter implements LoadBalancerBuilder<String, TestLoadBalancedConnection> {

    private RoundRobinLoadBalancerBuilder<String, TestLoadBalancedConnection> underlying;

    RoundRobinLoadBalancerBuilderAdapter(Class<?> clazz) {
        underlying = RoundRobinLoadBalancers.builder(requireNonNull(clazz, "clazz").getSimpleName());
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> loadBalancingPolicy(
            LoadBalancingPolicy<String, TestLoadBalancedConnection> loadBalancingPolicy) {
        throw new IllegalStateException("Cannot set new policy for old round robin");
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> backgroundExecutor(Executor backgroundExecutor) {
        underlying = underlying.backgroundExecutor(backgroundExecutor);
        return this;
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> linearSearchSpace(int linearSearchSpace) {
        underlying = underlying.linearSearchSpace(linearSearchSpace);
        return this;
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> healthCheckInterval(
            Duration interval, Duration jitter) {
        underlying = underlying.healthCheckInterval(interval, jitter);
        return this;
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> healthCheckResubscribeInterval(
            Duration interval, Duration jitter) {
        underlying = underlying.healthCheckResubscribeInterval(interval, jitter);
        return this;
    }

    @Override
    public LoadBalancerBuilder<String, TestLoadBalancedConnection> healthCheckFailedConnectionsThreshold(
            int threshold) {
        underlying = underlying.healthCheckFailedConnectionsThreshold(threshold);
        return this;
    }

    @Override
    public LoadBalancerFactory<String, TestLoadBalancedConnection> build() {
        return underlying.build();
    }
}
