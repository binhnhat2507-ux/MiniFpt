package com.miniftp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Runs the archived application's real-socket regressions under Maven/JUnit. */
class LegacyRegressionTest {
    @Test
    @Timeout(90)
    void originalTransferAndProtocolRegressions() throws Exception {
        MiniFtpIntegrationTest.main(new String[0]);
    }
}
