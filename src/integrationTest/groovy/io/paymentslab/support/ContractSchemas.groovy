package io.paymentslab.support

import groovy.yaml.YamlSlurper
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Checks parsed JSON bodies against the schemas in docs/api/openapi.yaml, so specs verify what the
 * server actually sends. Handles the JSON Schema keywords the contract uses ($ref, type, required,
 * properties, items, enum, const, format, minimum, maximum, maxLength). Stricter than JSON Schema
 * on purpose: a property the schema doesn't declare is a violation, because responses must not
 * leak internal fields.
 */
final class ContractSchemas {

    private static final Map CONTRACT = new YamlSlurper().parse(new File("docs/api/openapi.yaml")) as Map
    private static final String UUID_TEXT = /(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/

    private ContractSchemas() {}

    /** Every way {@code body} breaks components/schemas/{@code schemaName}; empty when it conforms. */
    static List<String> violations(Object body, String schemaName) {
        List<String> found = []
        check(body, ['$ref': "#/components/schemas/$schemaName".toString()], '$', found)
        found
    }

    // Bracket access throughout: "const" and "enum" are Groovy keywords, and some keys collide with GDK properties.
    private static void check(Object value, Map schema, String path, List<String> found) {
        if (schema.containsKey('$ref')) {
            check(value, resolve(schema['$ref'] as String) + schema.findAll { it.key != '$ref' }, path, found)
            return
        }
        if (schema.containsKey('const') && value != schema['const']) {
            found << "$path: expected ${schema['const']}, got $value".toString()
        }
        if (schema.containsKey('enum') && !(value in (schema['enum'] as List))) {
            found << "$path: $value is not one of ${schema['enum']}".toString()
        }
        if (schema.containsKey('type')) {
            List types = schema['type'] instanceof List ? schema['type'] as List : [schema['type']]
            if (!types.any { hasType(value, it as String) }) {
                found << "$path: expected ${types.join(' or ')}, got ${value == null ? 'null' : value.getClass().simpleName}".toString()
                return
            }
        }
        if (value instanceof Map) {
            Map properties = (schema['properties'] ?: [:]) as Map
            (schema['required'] ?: []).each { name ->
                if (!value.containsKey(name)) {
                    found << "$path: missing $name".toString()
                }
            }
            value.each { name, item ->
                if (properties.containsKey(name)) {
                    check(item, properties[name] as Map, "$path.$name", found)
                } else {
                    found << "$path: undeclared property $name".toString()
                }
            }
        }
        if (value instanceof List && schema['items'] != null) {
            value.eachWithIndex { item, index -> check(item, schema['items'] as Map, "$path[$index]", found) }
        }
        if (value instanceof String) {
            if (schema['maxLength'] != null && value.length() > (schema['maxLength'] as int)) {
                found << "$path: longer than ${schema['maxLength']}".toString()
            }
            if (schema['format'] == 'uuid' && !(value ==~ UUID_TEXT)) {
                found << "$path: $value is not a uuid".toString()
            }
            if (schema['format'] == 'date-time' && !isDateTime(value)) {
                found << "$path: $value is not an RFC 3339 date-time".toString()
            }
        }
        if (value instanceof Number) {
            if (schema['minimum'] != null && value < schema['minimum']) {
                found << "$path: $value is below ${schema['minimum']}".toString()
            }
            if (schema['maximum'] != null && value > schema['maximum']) {
                found << "$path: $value is above ${schema['maximum']}".toString()
            }
        }
    }

    private static boolean hasType(Object value, String type) {
        switch (type) {
            case 'null': return value == null
            case 'string': return value instanceof String
            case 'boolean': return value instanceof Boolean
            case 'integer': return value instanceof Integer || value instanceof Long || value instanceof BigInteger
            case 'number': return value instanceof Number
            case 'object': return value instanceof Map
            case 'array': return value instanceof List
            default: throw new IllegalArgumentException("unknown type '$type' in the contract")
        }
    }

    /** "#/components/schemas/Account" → that node of the contract. */
    private static Map resolve(String ref) {
        if (!ref.startsWith('#/')) {
            throw new IllegalArgumentException("only local references are supported: $ref")
        }
        def node = ref.substring(2).split('/').inject(CONTRACT) { Map current, String key -> current[key] as Map }
        if (node == null) {
            throw new IllegalArgumentException("no schema at $ref")
        }
        node as Map
    }

    private static boolean isDateTime(String value) {
        try {
            OffsetDateTime.parse(value)
            true
        } catch (DateTimeParseException ignored) {
            false
        }
    }
}
