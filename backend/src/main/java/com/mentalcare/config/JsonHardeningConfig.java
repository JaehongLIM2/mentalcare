package com.mentalcare.config;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defence in depth against HTML injection: every JSON response (and SSE data) escapes {@code < > & '}
 * as \\u00XX. React never renders strings as HTML, but a JSON body shown in a browser tab or copied
 * elsewhere should still not carry raw markup. The escapes stay valid JSON for every consumer.
 */
@Configuration
public class JsonHardeningConfig {

    static final class HtmlSafeEscapes extends CharacterEscapes {
        private final int[] escapes;

        HtmlSafeEscapes() {
            escapes = CharacterEscapes.standardAsciiEscapesForJSON();
            escapes['<'] = CharacterEscapes.ESCAPE_CUSTOM;
            escapes['>'] = CharacterEscapes.ESCAPE_CUSTOM;
            escapes['&'] = CharacterEscapes.ESCAPE_CUSTOM;
            escapes['\''] = CharacterEscapes.ESCAPE_CUSTOM;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return escapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return new SerializedString(String.format("\\u%04x", ch));
        }
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer htmlSafeJson() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setCharacterEscapes(new HtmlSafeEscapes()));
    }
}
