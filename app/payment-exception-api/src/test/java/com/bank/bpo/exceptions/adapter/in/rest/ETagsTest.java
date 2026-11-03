package com.bank.bpo.exceptions.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ETagsTest {

    @Test
    void readsTheFirstStrongTagOfAnIfMatchHeader() {
        assertThat(ETags.token("\"rt-42\"")).isEqualTo("rt-42");
        assertThat(ETags.token("\"rt-42\", \"rt-43\"")).isEqualTo("rt-42");
        assertThat(ETags.token("rt-42")).isEqualTo("rt-42");
    }

    @Test
    void namesNoTagForAnAbsentBlankOrWildcardHeader() {
        assertThat(ETags.token(null)).isNull();
        assertThat(ETags.token("  ")).isNull();
        assertThat(ETags.token("*")).isNull();
    }

    @Test
    void aWeakTagNeverMatchesAStrongOne() {
        assertThat(ETags.token("W/\"rt-42\"")).isNotEqualTo("rt-42");
        assertThat(ETags.token("\"")).isEqualTo("\"");
    }
}
