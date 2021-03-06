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
package org.apache.tinkerpop.gremlin.console

import org.codehaus.groovy.tools.shell.Command
import org.codehaus.groovy.tools.shell.Groovysh
import org.codehaus.groovy.tools.shell.ParseCode
import org.codehaus.groovy.tools.shell.Parser

/**
 * Overrides the posix style parsing of Groovysh allowing for commands to parse prior to Groovy 2.4.x.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
class GremlinGroovysh extends Groovysh {

    private final Mediator mediator

    public GremlinGroovysh(final Mediator mediator) {
        this.mediator = mediator
    }

    protected List parseLine(final String line) {
        assert line != null
        return line.trim().tokenize()
    }

    @Override
    Command findCommand(final String line, final List<String> parsedArgs = null) {
        def l = line ?: ""

        final List<String> args = parseLine(l)
        if (args.size() == 0) return null

        def cmd = registry.find(args[0])

        if (cmd != null && args.size() > 1 && parsedArgs != null) {
            parsedArgs.addAll(args[1..-1])
        }

        return cmd
    }

    @Override
    Object execute(final String line) {
        if (mediator.localEvaluation)
            return super.execute(line)
        else {
            assert line != null
            if (line.trim().size() == 0) {
                return null
            }

            maybeRecordInput(line)

            Object result

            if (isExecutable(line)) {
                result = executeCommand(line)
                if (result != null) setLastResult(result)
                return result
            }

            List<String> current = new ArrayList<String>(buffers.current())
            current << line

            // determine if this script is complete or not - if not it's a multiline script
            def status = parser.parse(current)

            switch (status.code) {
                case ParseCode.COMPLETE:
                    // concat script here because commands don't support multi-line
                    def script = String.join(Parser.getNEWLINE(), current)
                    setLastResult(mediator.currentRemote().submit([script]))
                    buffers.clearSelected()
                    break
                case ParseCode.INCOMPLETE:
                    buffers.updateSelected(current)
                    break
                case ParseCode.ERROR:
                    throw status.cause
                default:
                    // Should never happen
                    throw new Error("Invalid parse status: $status.code")
            }

            return result
        }
    }

    private void setLastResult(final Object result) {
        if (resultHook == null) {
            throw new IllegalStateException('Result hook is not set')
        }

        resultHook.call((Object)result)

        interp.context['_'] = result

        maybeRecordResult(result)
    }
}
