package com.rsreach.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class DevTokenExposureGateTest {

    @Mock
    private Environment environment;

    @Test
    void disabledWhenPropertyFalse() {
        RsReachSecurityProperties props = new RsReachSecurityProperties();
        props.setExposeDevTokens(false);
        DevTokenExposureGate gate = new DevTokenExposureGate(props, environment);
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void disabledOutsideLocalEvenIfPropertyTrue() {
        RsReachSecurityProperties props = new RsReachSecurityProperties();
        props.setExposeDevTokens(true);
        when(environment.matchesProfiles("local")).thenReturn(false);
        DevTokenExposureGate gate = new DevTokenExposureGate(props, environment);
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void disabledUnderDockerEvenIfLocalAndPropertyTrue() {
        RsReachSecurityProperties props = new RsReachSecurityProperties();
        props.setExposeDevTokens(true);
        when(environment.matchesProfiles("local")).thenReturn(true);
        when(environment.matchesProfiles("docker")).thenReturn(true);
        DevTokenExposureGate gate = new DevTokenExposureGate(props, environment);
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void enabledOnlyForExplicitLocalNonDocker() {
        RsReachSecurityProperties props = new RsReachSecurityProperties();
        props.setExposeDevTokens(true);
        when(environment.matchesProfiles("local")).thenReturn(true);
        when(environment.matchesProfiles("docker")).thenReturn(false);
        DevTokenExposureGate gate = new DevTokenExposureGate(props, environment);
        assertThat(gate.isEnabled()).isTrue();
    }
}
