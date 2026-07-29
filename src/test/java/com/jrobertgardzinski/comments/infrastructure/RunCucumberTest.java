package com.jrobertgardzinski.comments.infrastructure;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Runs the comment feature files through Cucumber against the Spring app, reporting to Allure —
 * the same living-documentation convention security, email and memes follow.
 */
@Suite
@IncludeEngines("cucumber")
// package selector, not the classpath-resource one: cucumber 7.34 deprecates
// @SelectClasspathResource for selecting features in a package and says so on every run
@SelectPackages("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.jrobertgardzinski.comments.infrastructure.cucumber")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
public class RunCucumberTest {
}
