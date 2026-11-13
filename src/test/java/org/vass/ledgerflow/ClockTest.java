package org.vass.ledgerflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
public class ClockTest {

    @Autowired
    private Clock clock;

    @Test
    void clockUsesUtcTest() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);

    }
}
