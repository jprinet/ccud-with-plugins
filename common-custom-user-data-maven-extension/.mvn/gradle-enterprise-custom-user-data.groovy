/*
Example of extending the core functionality of the Common Custom User Data Maven Extension with a Groovy script.
*/

// Log a debug message to the build output
log.debug('Evaluating my custom Groovy script')

// Add a build scan tag with the project name
buildScan.tag(project.name)

// Add a custom value based on a property of the `Session`
buildScan.value('parallel', session.parallel as String)

// Enable local build caching
buildCache.local.enabled = true
