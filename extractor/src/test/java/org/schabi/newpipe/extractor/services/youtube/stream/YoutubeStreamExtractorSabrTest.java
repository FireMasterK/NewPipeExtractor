package org.schabi.newpipe.extractor.services.youtube.stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.services.youtube.InitYoutubeTest;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;

import java.util.Base64;

/**
 * Tests for the SABR (Server-Adaptive BitRate) metadata getters added to
 * {@link YoutubeStreamExtractor} in this fork. The getters expose
 * {@code serverAbrStreamingUrl}, {@code videoPlaybackUstreamerConfig} and
 * {@code cpn} so downstream consumers (e.g. Piped-Backend) can drive a SABR
 * client without needing to parse {@code playerResponse} themselves.
 */
public class YoutubeStreamExtractorSabrTest {

    private static final String BASE_URL = "https://www.youtube.com/watch?v=";
    private static final String ID = "dQw4w9WgXcQ";

    public static class AfterFetchPage implements InitYoutubeTest {
        private static YoutubeStreamExtractor extractor;

        @BeforeAll
        @Override
        public void setUp() throws Exception {
            InitYoutubeTest.super.setUp();
            extractor = (YoutubeStreamExtractor) YouTube.getStreamExtractor(BASE_URL + ID);
            extractor.fetchPage();
        }

        @Test
        void androidServerAbrStreamingUrlIsValid() {
            final String url = extractor.getAndroidServerAbrStreamingUrl();
            assertNotNull(url, "ANDROID serverAbrStreamingUrl should be present");
            assertTrue(url.startsWith("https://"), "URL should be HTTPS: " + url);
            assertTrue(url.contains("googlevideo.com"),
                    "URL should point to googlevideo.com: " + url);
        }

        @Test
        void androidVideoPlaybackUstreamerConfigIsBase64() {
            final String config = extractor.getAndroidVideoPlaybackUstreamerConfig();
            assertNotNull(config, "ANDROID videoPlaybackUstreamerConfig should be present");
            // Empirically ~1.7-2.1 KB; pick a conservative floor that rejects empty/truncated
            // values while not coupling tests to YouTube's exact payload size.
            assertTrue(config.length() > 100,
                    "ustreamerConfig suspiciously short: " + config.length() + " chars");
            // Round-trip decode: catches non-base64 (would throw IllegalArgumentException).
            // YouTube emits URL-safe base64 (uses '-' and '_'), so use getUrlDecoder.
            final byte[] decoded = Base64.getUrlDecoder().decode(config);
            assertTrue(decoded.length > 0, "decoded ustreamerConfig should be non-empty");
        }

        @Test
        void androidCpnIsSixteenChars() {
            final String cpn = extractor.getAndroidCpn();
            assertNotNull(cpn, "ANDROID cpn should be present");
            // Content Playback Nonce is a fixed 16-char URL-safe base64 string in NPE
            assertTrue(cpn.length() == 16, "cpn length should be 16, got " + cpn.length());
        }
    }

    /**
     * Verifies the null-safety contract: getters return null (not NPE) when called
     * on an extractor whose {@code fetchPage()} has not yet run. Backend code relies
     * on this to decide whether to even attempt a SABR path.
     */
    public static class BeforeFetchPage implements InitYoutubeTest {
        private static YoutubeStreamExtractor extractor;

        @BeforeAll
        @Override
        public void setUp() throws Exception {
            InitYoutubeTest.super.setUp();
            extractor = (YoutubeStreamExtractor) YouTube.getStreamExtractor(BASE_URL + ID);
            // intentionally do NOT call extractor.fetchPage()
        }

        @Test
        void allSabrGettersReturnNull() {
            assertNull(extractor.getAndroidServerAbrStreamingUrl());
            assertNull(extractor.getIosServerAbrStreamingUrl());
            assertNull(extractor.getAndroidVideoPlaybackUstreamerConfig());
            assertNull(extractor.getIosVideoPlaybackUstreamerConfig());
            assertNull(extractor.getAndroidCpn());
            assertNull(extractor.getIosCpn());
        }
    }
}
