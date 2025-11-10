ruleset {
    description 'Morpheus SCVMM Plugin CodeNarc Rules'

    // Basic rules
    ruleset('rulesets/basic.xml')

    // Braces rules
    ruleset('rulesets/braces.xml')

    // Concurrency rules
    ruleset('rulesets/concurrency.xml')

    // Convention rules
    ruleset('rulesets/convention.xml') {
        // Exclude some overly strict naming rules
        exclude 'NoDef'
        exclude 'MethodReturnTypeRequired'
        exclude 'VariableTypeRequired'
    }

    // Design rules
    ruleset('rulesets/design.xml')

    // Dry rules
    ruleset('rulesets/dry.xml') {
        // Customize DRY rules
        'DuplicateStringLiteral' {
            doNotApplyToClassNames = '*Spec,*Test'
        }
        'DuplicateNumberLiteral' {
            doNotApplyToClassNames = '*Spec,*Test'
        }
    }

    // Enhanced rules
    ruleset('rulesets/enhanced.xml')

    // Exception rules
    ruleset('rulesets/exceptions.xml')

    // Generic rules
    ruleset('rulesets/generic.xml')

    // Grails rules (excluded since this isn't a Grails app)
    // ruleset('rulesets/grails.xml')

    // Groovy rules
    ruleset('rulesets/groovyism.xml')

    // Imports rules
    ruleset('rulesets/imports.xml')

    // JUnit rules
    ruleset('rulesets/junit.xml')

    // Logging rules
    ruleset('rulesets/logging.xml')

    // Naming rules
    ruleset('rulesets/naming.xml') {
        // Exclude some overly strict naming rules
        exclude 'MethodName'
        exclude 'FactoryMethodName'
    }

    // Security rules
    ruleset('rulesets/security.xml')

    // Serialization rules
    ruleset('rulesets/serialization.xml')

    // Size and complexity rules
    ruleset('rulesets/size.xml') {
        // Customize size rules
        'MethodSize' {
            maxLines = 50
        }
        'ClassSize' {
            maxLines = 500
        }
        'MethodCount' {
            maxMethods = 35
        }
        'CyclomaticComplexity' {
            maxMethodComplexity = 15
        }
    }

    // Unnecessary rules
    ruleset('rulesets/unnecessary.xml') {
        // Exclude some unnecessary rules
        exclude 'UnnecessaryGetter'
        exclude 'UnnecessaryObjectReferences'
    }

    // Unused rules
    ruleset('rulesets/unused.xml')
}
