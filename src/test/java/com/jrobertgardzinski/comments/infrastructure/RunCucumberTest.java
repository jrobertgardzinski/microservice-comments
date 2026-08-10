package com.jrobertgardzinski.comments.infrastructure;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Runs the feature files through Cucumber against the Spring app, reporting to Allure —
 * the same living-documentation convention security, email and memes follow.
 */
@Suite
@IncludeEngines("cucumber")
// the top-level specs/ dir is the entry point into this service — one Gherkin file per use case,
// pulled onto the classpath root by build-helper (microservice-security's convention) and selected
// explicitly, file by file (selecting single files, unlike selecting a package, is not deprecated)
@SelectClasspathResource("add-comment.feature")
@SelectClasspathResource("list-comments.feature")
@SelectClasspathResource("vote-on-comment.feature")
@SelectClasspathResource("delete-comment.feature")
@SelectClasspathResource("delete-thread.feature")
@SelectClasspathResource("hide-comment.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.jrobertgardzinski.comments.infrastructure.cucumber")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
public class RunCucumberTest {
}
