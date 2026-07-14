package st.orm.demo.imdb

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * The application's own reachability metadata. Storm ships its native-image
 * metadata with storm-core and registers entities, repositories, and their
 * proxies through the Spring AOT hints in storm-spring, so everything below
 * is an application concern, not a Storm one:
 *
 * - **Templates**: Thymeleaf evaluates expressions through SpEL, which
 *   invokes methods reflectively on whatever the expressions touch: the
 *   #numbers/#strings/#uris utility objects and plain JDK value and
 *   collection types (even String.isEmpty()).
 * - **Jackson 3**: Spring resolves the JsonMapper reflectively when it
 *   builds the HTTP message converters.
 * - **View models**: query result shapes and API models are read
 *   reflectively by Thymeleaf/SpEL and the serialization infrastructure.
 */
internal class ApplicationRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // Jackson 3, resolved reflectively by the message converter setup.
        listOf("tools.jackson.databind.json.JsonMapper",
               "tools.jackson.databind.json.JsonMapper\$Builder").forEach { name ->
            hints.reflection().registerType(TypeReference.of(name),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS)
        }

        // Types the Thymeleaf templates touch through SpEL expressions.
        listOf(
            "org.thymeleaf.expression.Numbers",
            "org.thymeleaf.expression.Strings",
            "org.thymeleaf.expression.Uris",
            "java.lang.String", "java.lang.Integer", "java.lang.Long",
            "java.lang.Boolean", "java.lang.Double", "java.math.BigDecimal",
            "java.time.Instant",
            "java.util.ArrayList",
            "java.util.LinkedHashMap",
            "java.util.Collections\$SingletonList",
            "java.util.Collections\$UnmodifiableList",
            "java.util.Collections\$UnmodifiableRandomAccessList",
            "java.util.ImmutableCollections\$List12",
            "java.util.ImmutableCollections\$ListN",
            "java.util.ImmutableCollections\$SubList",
            "kotlin.collections.EmptyList").forEach { name ->
            hints.reflection().registerType(TypeReference.of(name),
                MemberCategory.INVOKE_PUBLIC_METHODS)
        }

        // View models, result shapes, API models, and generated kotlinx
        // serializers from the application packages.
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter { _, _ -> true }
        listOf("model", "repository", "service", "serialization", "web").forEach { subpackage ->
            scanner.findCandidateComponents("st.orm.demo.imdb.$subpackage")
                .mapNotNull { it.beanClassName }
                .forEach { name ->
                    hints.reflection().registerType(TypeReference.of(name),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS)
                }
        }
    }
}
