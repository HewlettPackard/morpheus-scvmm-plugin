// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.support

import com.morpheusdata.model.TaskResult
import groovy.json.JsonOutput

/**
 * Canned {@link TaskResult}s for stubbing the PowerShell boundary of {@code ScvmmApiService}.
 * {@code ok}/{@code fail} mimic {@code wrapExecuteCommand} (data already JSON-parsed);
 * {@code raw} mimics {@code executeCommand} (data is the unparsed stdout String).
 */
class TaskResults {

    static TaskResult ok(Object data = [], String exitCode = '0') {
        new TaskResult(success: true, exitCode: exitCode, data: data)
    }

    static TaskResult fail(String error, Object data = null, Integer exitCode = 1) {
        new TaskResult(success: false, exitCode: exitCode?.toString(), error: error, data: data)
    }

    static TaskResult raw(String stdout, int exitCode = 0) {
        new TaskResult(success: exitCode == 0, exitCode: exitCode.toString(), data: stdout, output: stdout)
    }

    /** PsStub value producing a fresh {@link #raw} result with {@code payload} serialised as ConvertTo-Json stdout. */
    static Closure json(Object payload, int exitCode = 0) {
        return { String cmd -> raw(JsonOutput.toJson(payload), exitCode) }
    }
}
