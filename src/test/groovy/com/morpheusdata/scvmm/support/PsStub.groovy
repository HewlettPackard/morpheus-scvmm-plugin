// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

import com.morpheusdata.model.TaskResult

import java.util.regex.Pattern

/**
 * Response closure for {@code wrapExecuteCommand}/{@code executeCommand} stubs that records every script and
 * answers by the first matching key, so multi-call methods stay deterministic:
 * <pre>
 * def ps = PsStub.capture(scripts, [(~/New-SCVMCheckpoint/): TaskResults.ok()])
 * svc.wrapExecuteCommand(_ as String, _ as Map) >> { String cmd, Map o -> ps(cmd, o) }
 * </pre>
 * The response must be a closure <em>literal</em>: Spock treats {@code >> PsStub.capture(...)} as a constant
 * response and would return the Closure object itself.
 * Keys: {@link Pattern} (find) or String (contains), checked in insertion order.
 * Values: a {@link TaskResult}; a {@code List} answered in order (last one repeats);
 * or a {@code Closure} taking the script and returning a TaskResult (also allowed as a List element).
 * Use closures (e.g. {@link TaskResults#json}) when stubbing {@code executeCommand}/{@code executeWindowsCommand}:
 * {@code wrapExecuteCommand} replaces {@code out.data} in place, so a reused TaskResult breaks the second call.
 * <p>
 * Groovy gotcha: on a {@code Spy(ScvmmApiService)}, production closures that touch the private {@code log}
 * field fail with {@code MissingMethodException: getLog()} (e.g. the paging {@code fetch} closures in the
 * list* methods). For those, use a real {@code ScvmmApiService} and stub {@code context.executeWindowsCommand}.
 */
class PsStub {

    static Closure capture(List<String> into, Map byCommand = [:], TaskResult fallback = TaskResults.ok()) {
        Map<Object, Object> responses = new LinkedHashMap(byCommand)
        Map<Object, Integer> cursors = [:]
        return { String cmd, Map opts = [:] ->
            into?.add(cmd)
            def key = responses.keySet().find { matches(it, cmd) }
            if (key == null) {
                return fallback
            }
            def value = responses[key]
            if (value instanceof List) {
                int idx = cursors.get(key, 0)
                cursors[key] = idx + 1
                value = value[Math.min(idx, value.size() - 1)]
            }
            return value instanceof Closure ? value.call(cmd) : value
        }
    }

    private static boolean matches(Object key, String cmd) {
        key instanceof Pattern ? ((Pattern) key).matcher(cmd).find() : cmd.contains(key.toString())
    }
}
