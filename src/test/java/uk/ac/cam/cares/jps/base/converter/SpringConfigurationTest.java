package uk.ac.cam.cares.jps.base.converter;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

public class SpringConfigurationTest {

    @Test
    public void PropertySourcesPlaceholderConfigurerTest(){
        assertNotNull(SpringConfiguration.propertyConfigInDev());

    }
}
