package dev.codyqiu.scheduler.experiment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * The identity of a submission, compared when a client reuses an Idempotency-Key.
 *
 * <p>It is a SHA-256 over a canonical form of the validated request, not over the raw body, so
 * only meaning counts:
 * <ul>
 * <li>Object keys are sorted, so JSON key order does not matter.</li>
 * <li>Defaults are applied: an omitted {@code maxAttempts} equals an explicit 3.</li>
 * <li>Floating-point values are written as plain decimals of the parsed double, so
 * {@code 0.0001}, {@code 1e-4} and {@code 1.0E-4} are the same.</li>
 * </ul>
 * Job order does matter, because it decides job indexes. The canonical form is derived from the
 * request records themselves, so a field added later cannot be left out of the identity.
 */
public final class RequestFingerprint {

	/** Bump when the canonical form changes; fingerprints of different versions never match. */
	static final String VERSION = "v1";

	/** Plain settings: the canonical text is built here, not shaped by the API's JSON settings. */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private RequestFingerprint() {
	}

	public static String of(CreateExperimentRequest request) {
		Map<String, Object> normalized = new TreeMap<>();
		normalized.put("name", request.name());
		normalized.put("task", request.task().id());
		normalized.put("maxAttempts", request.effectiveMaxAttempts());
		normalized.put("jobs", request.jobs());
		Object canonical = canonicalize(JSON.convertValue(normalized, Object.class));
		return VERSION + ":" + sha256(JSON.writeValueAsString(canonical));
	}

	/** Sorts map keys and turns floating-point numbers into plain decimal text, recursively. */
	private static Object canonicalize(Object value) {
		return switch (value) {
			case Map<?, ?> map -> {
				Map<String, Object> sorted = new TreeMap<>();
				map.forEach((key, child) -> sorted.put((String) key, canonicalize(child)));
				yield sorted;
			}
			case List<?> list -> list.stream().map(RequestFingerprint::canonicalize).toList();
			case Double number -> new BigDecimal(Double.toString(number)).stripTrailingZeros().toPlainString();
			case Float number -> canonicalize(number.doubleValue());
			case null -> null;
			default -> value;
		};
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
		}
	}

}
