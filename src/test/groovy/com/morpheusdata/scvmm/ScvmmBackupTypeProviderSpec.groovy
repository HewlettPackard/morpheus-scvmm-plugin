package com.morpheusdata.scvmm

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject

class ScvmmBackupTypeProviderSpec extends Specification {

    @Subject
    ScvmmBackupTypeProvider provider

    ScvmmPlugin mockPlugin
    MorpheusContext mockMorpheusContext

    def setup() {
        mockPlugin = Mock(ScvmmPlugin)
        mockMorpheusContext = Mock(MorpheusContext)
        provider = new ScvmmBackupTypeProvider(mockPlugin, mockMorpheusContext)
    }

    def "constructor should initialize with plugin and morpheusContext"() {
        when:
        def newProvider = new ScvmmBackupTypeProvider(mockPlugin, mockMorpheusContext)

        then:
        newProvider.plugin == mockPlugin
        newProvider.morpheusContext == mockMorpheusContext
        // Note: executionProvider and restoreProvider are lazily initialized, so they might not be null
    }

    def "getCode should return unique provider code"() {
        when:
        def code = provider.getCode()

        then:
        code == "scvmmSnapshot"
        code instanceof String
        code.length() > 0
    }

    def "getName should return human readable provider name"() {
        when:
        def name = provider.getName()

        then:
        name == "SCVMM VM Snapshot"
        name instanceof String
        name.length() > 0
    }

    def "getContainerType should return correct container type"() {
        when:
        def containerType = provider.getContainerType()

        then:
        containerType == "single"
        containerType instanceof String
    }

    def "getCopyToStore should return false indicating no copy to store support"() {
        when:
        def copyToStore = provider.getCopyToStore()

        then:
        copyToStore == false
        copyToStore instanceof Boolean
    }

    def "getDownloadEnabled should return false indicating download is not supported"() {
        when:
        def downloadEnabled = provider.getDownloadEnabled()

        then:
        downloadEnabled == false
        downloadEnabled instanceof Boolean
    }

    def "getRestoreExistingEnabled should return true indicating restore to existing is supported"() {
        when:
        def restoreExistingEnabled = provider.getRestoreExistingEnabled()

        then:
        restoreExistingEnabled == true
        restoreExistingEnabled instanceof Boolean
    }

    def "getRestoreNewEnabled should return false indicating restore to new is not supported"() {
        when:
        def restoreNewEnabled = provider.getRestoreNewEnabled()

        then:
        restoreNewEnabled == false
        restoreNewEnabled instanceof Boolean
    }

    def "getRestoreType should return offline restore type"() {
        when:
        def restoreType = provider.getRestoreType()

        then:
        restoreType == "offline"
        restoreType instanceof String
    }

    def "getRestoreNewMode should return null indicating default behavior"() {
        when:
        def restoreNewMode = provider.getRestoreNewMode()

        then:
        restoreNewMode == null
    }

    def "getHasCopyToStore should return false indicating no copy to store capability"() {
        when:
        def hasCopyToStore = provider.getHasCopyToStore()

        then:
        hasCopyToStore == false
        hasCopyToStore instanceof Boolean
    }

    def "getOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
        optionTypes.size() == 0
    }

    def "getExecutionProvider should return ScvmmBackupExecutionProvider instance"() {
        when:
        def executionProvider = provider.getExecutionProvider()

        then:
        executionProvider != null
        executionProvider instanceof ScvmmBackupExecutionProvider
        executionProvider instanceof BackupExecutionProvider
        // Should cache the instance
        provider.getExecutionProvider() == executionProvider
    }

    def "getExecutionProvider should cache instance on subsequent calls"() {
        when:
        def firstCall = provider.getExecutionProvider()
        def secondCall = provider.getExecutionProvider()

        then:
        firstCall != null
        secondCall != null
        // Should return the same cached instance in single-threaded access
        firstCall == secondCall
        // Verify both are the expected type
        firstCall instanceof ScvmmBackupExecutionProvider
        secondCall instanceof ScvmmBackupExecutionProvider
    }

    def "getRestoreProvider should return ScvmmBackupRestoreProvider instance"() {
        when:
        def restoreProvider = provider.getRestoreProvider()

        then:
        restoreProvider != null
        restoreProvider instanceof ScvmmBackupRestoreProvider
        restoreProvider instanceof BackupRestoreProvider
        // Should cache the instance
        provider.getRestoreProvider() == restoreProvider
    }

    def "getRestoreProvider should cache instance on subsequent calls"() {
        when:
        def firstCall = provider.getRestoreProvider()
        def secondCall = provider.getRestoreProvider()

        then:
        firstCall != null
        secondCall != null
        // Should return the same cached instance in single-threaded access
        firstCall == secondCall
        // Verify both are the expected type
        firstCall instanceof ScvmmBackupRestoreProvider
        secondCall instanceof ScvmmBackupRestoreProvider
    }

    def "refresh should return successful ServiceResponse"() {
        given:
        def authConfig = [username: "testuser", password: "testpass"]
        def backupProvider = new BackupProviderModel()

        when:
        def result = provider.refresh(authConfig, backupProvider)

        then:
        result != null
        result instanceof ServiceResponse
        result.success == true
        result.data == null
        result.msg == null
        // ServiceResponse.errors is typically an empty map, not null
        result.errors == [:] || result.errors == null
    }

    def "refresh should handle null authConfig gracefully"() {
        given:
        def backupProvider = new BackupProviderModel()

        when:
        def result = provider.refresh(null, backupProvider)

        then:
        result != null
        result.success == true
    }

    def "refresh should handle null backupProvider gracefully"() {
        given:
        def authConfig = [:]

        when:
        def result = provider.refresh(authConfig, null)

        then:
        result != null
        result.success == true
    }

    def "clean should return successful ServiceResponse"() {
        given:
        def backupProvider = new BackupProviderModel()
        def opts = [cleanAll: true]

        when:
        def result = provider.clean(backupProvider, opts)

        then:
        result != null
        result instanceof ServiceResponse
        result.success == true
        result.data == null
        result.msg == null
        // ServiceResponse.errors is typically an empty map, not null
        result.errors == [:] || result.errors == null
    }

    def "clean should handle null backupProvider gracefully"() {
        given:
        def opts = [:]

        when:
        def result = provider.clean(null, opts)

        then:
        result != null
        result.success == true
    }

    def "clean should handle null opts gracefully"() {
        given:
        def backupProvider = new BackupProviderModel()

        when:
        def result = provider.clean(backupProvider, null)

        then:
        result != null
        result.success == true
    }

    def "provider capabilities should be consistent with SCVMM snapshot functionality"() {
        when:
        def capabilities = [
            code: provider.getCode(),
            name: provider.getName(),
            containerType: provider.getContainerType(),
            copyToStore: provider.getCopyToStore(),
            downloadEnabled: provider.getDownloadEnabled(),
            restoreExistingEnabled: provider.getRestoreExistingEnabled(),
            restoreNewEnabled: provider.getRestoreNewEnabled(),
            restoreType: provider.getRestoreType(),
            restoreNewMode: provider.getRestoreNewMode(),
            hasCopyToStore: provider.getHasCopyToStore()
        ]

        then:
        // Verify SCVMM snapshot specific capabilities
        capabilities.code == "scvmmSnapshot"
        capabilities.name.contains("SCVMM")
        capabilities.name.contains("Snapshot")
        capabilities.containerType == "single"

        // SCVMM snapshots typically don't support these features
        capabilities.copyToStore == false
        capabilities.downloadEnabled == false
        capabilities.hasCopyToStore == false
        capabilities.restoreNewEnabled == false

        // SCVMM snapshots support restore to existing but offline
        capabilities.restoreExistingEnabled == true
        capabilities.restoreType == "offline"
        capabilities.restoreNewMode == null
    }

    def "provider should maintain state correctly across multiple method calls"() {
        when:
        def firstExecution = provider.getExecutionProvider()
        def firstRestore = provider.getRestoreProvider()
        def secondExecution = provider.getExecutionProvider()
        def secondRestore = provider.getRestoreProvider()

        then:
        // Verify caching works correctly (should be same instances)
        firstExecution == secondExecution
        firstRestore == secondRestore

        // Verify different instances for different provider types
        firstExecution != firstRestore

        // Verify both types are correct
        firstExecution instanceof ScvmmBackupExecutionProvider
        firstRestore instanceof ScvmmBackupRestoreProvider
        secondExecution instanceof ScvmmBackupExecutionProvider
        secondRestore instanceof ScvmmBackupRestoreProvider

        // Verify provider references are maintained
        provider.executionProvider == firstExecution
        provider.restoreProvider == firstRestore
    }

    def "all string properties should be non-null and non-empty"() {
        when:
        def stringProperties = [
            provider.getCode(),
            provider.getName(),
            provider.getContainerType(),
            provider.getRestoreType()
        ]

        then:
        stringProperties.each { property ->
            assert property != null
            assert property instanceof String
            assert property.length() > 0
            assert !property.trim().isEmpty()
        }
    }

    def "all boolean properties should return proper Boolean instances"() {
        when:
        def booleanProperties = [
            provider.getCopyToStore(),
            provider.getDownloadEnabled(),
            provider.getRestoreExistingEnabled(),
            provider.getRestoreNewEnabled(),
            provider.getHasCopyToStore()
        ]

        then:
        booleanProperties.each { property ->
            assert property != null
            assert property instanceof Boolean
            assert property == true || property == false
        }
    }
}
