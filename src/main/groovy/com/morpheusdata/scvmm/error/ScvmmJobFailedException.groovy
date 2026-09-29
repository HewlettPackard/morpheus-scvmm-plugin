// Copyright 2026 Hewlett Packard Enterprise Development LP

package com.morpheusdata.scvmm.error

/**
 * An asynchronous VMM job finished in a failed state.
 */
class ScvmmJobFailedException extends ScvmmException {
    final String jobId
    final String jobName
    final String jobStatus
    final String errorInfo

    ScvmmJobFailedException(String jobId, String jobName, String jobStatus, String errorInfo, Throwable cause = null) {
        super(buildMessage(jobId, jobName, jobStatus, errorInfo), buildUserMessage(jobName, errorInfo), cause)
        this.jobId = jobId
        this.jobName = jobName
        this.jobStatus = jobStatus
        this.errorInfo = errorInfo
    }

    private static String buildMessage(String jobId, String jobName, String jobStatus, String errorInfo) {
        "SCVMM job ${jobId}${jobName ? " (${jobName})" : ''} finished with status '${jobStatus}'${errorInfo ? ": ${errorInfo}" : ''}".toString()
    }

    private static String buildUserMessage(String jobName, String errorInfo) {
        String what = jobName ? "SCVMM job '${jobName}'" : 'The SCVMM job'
        errorInfo ? "${what} failed: ${ScvmmCommandSanitizer.firstLine(errorInfo)}".toString() : "${what} failed. Check the Jobs view in the VMM console for details.".toString()
    }

    @Override
    Map<String, Object> toDetailMap() {
        Map<String, Object> rtn = super.toDetailMap()
        rtn.jobId = jobId
        rtn.jobName = jobName
        rtn.jobStatus = jobStatus
        rtn.errorInfo = errorInfo
        rtn
    }
}
