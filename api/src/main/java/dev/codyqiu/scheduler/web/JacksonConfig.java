package dev.codyqiu.scheduler.web;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict request parsing. A request that does not say exactly what it means is rejected rather
 * than "fixed up", because a silently altered config would run an experiment nobody asked for.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

	@Bean
	JsonMapperBuilderCustomizer strictJsonMapper() {
		return builder -> builder
			// A misspelled hyperparameter is an error, not an ignored field (Jackson 3 ignores by default).
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			// "epochs": 20.5 is an error, not 20.
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			// "hiddenUnits": "64" is an error, not 64.
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			// {"epochs": 1, "epochs": 100} is an error, not "last one wins".
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			// Responses keep record declaration order (Jackson 3 sorts alphabetically by default).
			.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
	}

}
