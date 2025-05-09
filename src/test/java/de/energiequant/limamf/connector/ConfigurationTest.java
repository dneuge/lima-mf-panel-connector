package de.energiequant.limamf.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import de.energiequant.apputils.misc.DisclaimerState;

class ConfigurationTest {
    @Test
    void testCreateFromDefaults_always_doesNotFail() {
        // arrange
        DisclaimerState disclaimerState = mockDisclaimerState();

        // act
        ThrowingCallable action = () -> Configuration.createFromDefaults(disclaimerState);

        // assert
        assertThatCode(action).doesNotThrowAnyException();
    }

    @Test
    void testCreateFromDefaults_always_hasNoAcceptedDisclaimer() {
        // arrange
        DisclaimerState disclaimerState = mockDisclaimerState();
        Configuration config = Configuration.createFromDefaults(disclaimerState);

        // act
        Optional<String> result = config.getAcceptedDisclaimer();

        // assert
        assertThat(result).isEmpty();
    }

    private static DisclaimerState mockDisclaimerState() {
        DisclaimerState disclaimerState = mock(DisclaimerState.class, RETURNS_DEEP_STUBS);
        when(disclaimerState.getDisclaimerHash()).thenReturn("1234");
        return disclaimerState;
    }
}
