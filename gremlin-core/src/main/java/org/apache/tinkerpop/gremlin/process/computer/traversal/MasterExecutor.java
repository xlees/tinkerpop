/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.tinkerpop.gremlin.process.computer.traversal;

import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Barrier;
import org.apache.tinkerpop.gremlin.process.traversal.step.LocalBarrier;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.LabelStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyKeyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyValueStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SackStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMatrix;
import org.apache.tinkerpop.gremlin.structure.util.Attachable;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedFactory;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class MasterExecutor {

    private MasterExecutor() {

    }

    protected static void processMemory(final TraversalMatrix<?, ?> traversalMatrix, final Memory memory, final TraverserSet<Object> traverserSet, final Set<String> completedBarriers) {
        if (memory.exists(TraversalVertexProgram.MUTATED_MEMORY_KEYS)) {
            for (final String key : memory.<Set<String>>get(TraversalVertexProgram.MUTATED_MEMORY_KEYS)) {
                final Step<Object, Object> step = traversalMatrix.getStepById(key);
                if (null == step) continue;
                assert step instanceof Barrier;
                completedBarriers.add(step.getId());
                if (!(step instanceof LocalBarrier)) {  // local barriers don't do any processing on the master traversal (they just lock on the workers)
                    final Barrier<Object> barrier = (Barrier<Object>) step;
                    barrier.addBarrier(memory.get(key));
                    while (step.hasNext()) {
                        traverserSet.add(step.next());
                    }
                    if (step instanceof ReducingBarrierStep)
                        memory.set(step.getId(), ((ReducingBarrierStep) step).getSeedSupplier().get());
                }
            }
        }
        memory.set(TraversalVertexProgram.MUTATED_MEMORY_KEYS, new HashSet<>());
    }

    protected static void processTraversers(final PureTraversal<?, ?> traversal,
                                            final TraversalMatrix<?, ?> traversalMatrix,
                                            TraverserSet<Object> toProcessTraversers,
                                            final TraverserSet<Object> remoteActiveTraversers,
                                            final TraverserSet<Object> haltedTraversers) {


        while (!toProcessTraversers.isEmpty()) {
            final TraverserSet<Object> localActiveTraversers = new TraverserSet<>();
            Step<Object, Object> previousStep = EmptyStep.instance();
            Step<Object, Object> currentStep = EmptyStep.instance();

            final Iterator<Traverser.Admin<Object>> traversers = IteratorUtils.removeOnNext(toProcessTraversers.iterator());
            while (traversers.hasNext()) {
                final Traverser.Admin<Object> traverser = traversers.next();
                traverser.set(DetachedFactory.detach(traverser.get(), true));
                traverser.setSideEffects(traversal.get().getSideEffects());
                if (traverser.isHalted()) {
                    traverser.detach();
                    haltedTraversers.add(traverser);
                } else if (isRemoteTraverser(traverser, traversalMatrix)) {  // this is so that patterns like order().name work as expected.
                    traverser.detach();
                    remoteActiveTraversers.add(traverser);
                } else {
                    currentStep = traversalMatrix.getStepById(traverser.getStepId());
                    if (!currentStep.getId().equals(previousStep.getId()) && !(previousStep instanceof EmptyStep)) {
                        while (previousStep.hasNext()) {
                            final Traverser.Admin<Object> result = previousStep.next();
                            if (result.isHalted()) {
                                result.detach();
                                haltedTraversers.add(result);
                            } else {
                                if (isRemoteTraverser(result, traversalMatrix)) {
                                    result.detach();
                                    remoteActiveTraversers.add(result);
                                } else
                                    localActiveTraversers.add(result);
                            }
                        }
                    }
                    currentStep.addStart(traverser);
                    previousStep = currentStep;
                }
            }
            if (!(currentStep instanceof EmptyStep)) {
                while (currentStep.hasNext()) {
                    final Traverser.Admin<Object> traverser = currentStep.next();
                    if (traverser.isHalted()) {
                        traverser.detach();
                        haltedTraversers.add(traverser);
                    } else {
                        if (isRemoteTraverser(traverser, traversalMatrix)) {
                            traverser.detach();
                            remoteActiveTraversers.add(traverser);
                        } else
                            localActiveTraversers.add(traverser);
                    }
                }
            }
            assert toProcessTraversers.isEmpty();
            toProcessTraversers = localActiveTraversers;
        }
    }

    private static boolean isRemoteTraverser(final Traverser.Admin traverser, final TraversalMatrix<?, ?> traversalMatrix) {
        return traverser.get() instanceof Attachable &&
                !(traverser.get() instanceof Path) &&
                !isLocalElement(traversalMatrix.getStepById(traverser.getStepId()));
    }

    // TODO: once this is complete (fully known), move to TraversalHelper
    private static boolean isLocalElement(final Step<?, ?> step) {
        return step instanceof PropertiesStep || step instanceof PropertyMapStep ||
                step instanceof IdStep || step instanceof LabelStep || step instanceof SackStep ||
                step instanceof PropertyKeyStep || step instanceof PropertyValueStep ||
                step instanceof TailGlobalStep || step instanceof RangeGlobalStep || step instanceof HasStep;
    }
}