/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.ClassWriter;
import org.elasticsearch.painless.CompilerSettings;
import org.elasticsearch.painless.Globals;
import org.elasticsearch.painless.Locals;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.ScriptRoot;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.Set;

import static java.util.Collections.emptyList;

/**
 * Represents a for loop.
 */
public final class SFor extends AStatement {

    private ANode initializer;
    private AExpression condition;
    private AExpression afterthought;
    private final SBlock block;

    private boolean continuous = false;

    public SFor(Location location, ANode initializer, AExpression condition, AExpression afterthought, SBlock block) {
        super(location);

        this.initializer = initializer;
        this.condition = condition;
        this.afterthought = afterthought;
        this.block = block;
    }

    @Override
    void storeSettings(CompilerSettings settings) {
        if (initializer != null) {
            initializer.storeSettings(settings);
        }

        if (condition != null) {
            condition.storeSettings(settings);
        }

        if (afterthought != null) {
            afterthought.storeSettings(settings);
        }

        if (block != null) {
            block.storeSettings(settings);
        }
    }

    @Override
    void extractVariables(Set<String> variables) {
        if (initializer != null) {
            initializer.extractVariables(variables);
        }

        if (condition != null) {
            condition.extractVariables(variables);
        }

        if (afterthought != null) {
            afterthought.extractVariables(variables);
        }

        if (block != null) {
            block.extractVariables(variables);
        }
    }

    @Override
    void analyze(ScriptRoot scriptRoot, Locals locals) {
        locals = Locals.newLocalScope(locals);

        if (initializer != null) {
            if (initializer instanceof SDeclBlock) {
                initializer.analyze(scriptRoot, locals);
            } else if (initializer instanceof AExpression) {
                AExpression initializer = (AExpression)this.initializer;

                initializer.read = false;
                initializer.analyze(scriptRoot, locals);

                if (!initializer.statement) {
                    throw createError(new IllegalArgumentException("Not a statement."));
                }

                initializer.expected = initializer.actual;
                this.initializer = initializer.cast(scriptRoot, locals);
            } else {
                throw createError(new IllegalStateException("Illegal tree structure."));
            }
        }

        if (condition != null) {
            condition.expected = boolean.class;
            condition.analyze(scriptRoot, locals);
            condition = condition.cast(scriptRoot, locals);

            if (condition.constant != null) {
                continuous = (boolean)condition.constant;

                if (!continuous) {
                    throw createError(new IllegalArgumentException("Extraneous for loop."));
                }

                if (block == null) {
                    throw createError(new IllegalArgumentException("For loop has no escape."));
                }
            }
        } else {
            continuous = true;
        }

        if (afterthought != null) {
            afterthought.read = false;
            afterthought.analyze(scriptRoot, locals);

            if (!afterthought.statement) {
                throw createError(new IllegalArgumentException("Not a statement."));
            }

            afterthought.expected = afterthought.actual;
            afterthought = afterthought.cast(scriptRoot, locals);
        }

        if (block != null) {
            block.beginLoop = true;
            block.inLoop = true;

            block.analyze(scriptRoot, locals);

            if (block.loopEscape && !block.anyContinue) {
                throw createError(new IllegalArgumentException("Extraneous for loop."));
            }

            if (continuous && !block.anyBreak) {
                methodEscape = true;
                allEscape = true;
            }

            block.statementCount = Math.max(1, block.statementCount);
        }

        statementCount = 1;

        if (locals.hasVariable(Locals.LOOP)) {
            loopCounter = locals.getVariable(location, Locals.LOOP);
        }
    }

    @Override
    void write(ClassWriter classWriter, MethodWriter methodWriter, Globals globals) {
        methodWriter.writeStatementOffset(location);

        Label start = new Label();
        Label begin = afterthought == null ? start : new Label();
        Label end = new Label();

        if (initializer instanceof SDeclBlock) {
            initializer.write(classWriter, methodWriter, globals);
        } else if (initializer instanceof AExpression) {
            AExpression initializer = (AExpression)this.initializer;

            initializer.write(classWriter, methodWriter, globals);
            methodWriter.writePop(MethodWriter.getType(initializer.expected).getSize());
        }

        methodWriter.mark(start);

        if (condition != null && !continuous) {
            condition.write(classWriter, methodWriter, globals);
            methodWriter.ifZCmp(Opcodes.IFEQ, end);
        }

        boolean allEscape = false;

        if (block != null) {
            allEscape = block.allEscape;

            int statementCount = Math.max(1, block.statementCount);

            if (afterthought != null) {
                ++statementCount;
            }

            if (loopCounter != null) {
                methodWriter.writeLoopCounter(loopCounter.getSlot(), statementCount, location);
            }

            block.continu = begin;
            block.brake = end;
            block.write(classWriter, methodWriter, globals);
        } else {
            if (loopCounter != null) {
                methodWriter.writeLoopCounter(loopCounter.getSlot(), 1, location);
            }
        }

        if (afterthought != null) {
            methodWriter.mark(begin);
            afterthought.write(classWriter, methodWriter, globals);
            methodWriter.writePop(MethodWriter.getType(afterthought.expected).getSize());
        }

        if (afterthought != null || !allEscape) {
            methodWriter.goTo(start);
        }

        methodWriter.mark(end);
    }

    @Override
    public String toString() {
        return multilineToString(emptyList(), Arrays.asList(initializer, condition, afterthought, block));
    }
}
