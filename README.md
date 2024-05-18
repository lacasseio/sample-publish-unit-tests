# Publish Unit Tests Sample

## Consume unit tests via project dependencies

Note the dependency will have to use the `com.example.testsuite.name=test` attribute.

```
$ ./gradlew verify -PdependencyType=project
> Task :test-aggregator:verify
./app/build/exe/test/macos/appTest
./lib/build/exe/test/libTest

BUILD SUCCESSFUL
```

## Consume unit tests via binary dependencies

First, we need to publish the unit test.

```
$ ./gradlew publishTestPublicationToMavenRepository publishTestMacosPublicationToMavenRepository
[...]

BUILD SUCCESSFUL
```

Then, we can consume the binary.
Note that because we publish the unit test as a distinct coordinate, we don't need to use the `com.example.testsuite.name` attribute.

```
$ ./gradlew verify -PdependencyType=binary
> Task :test-aggregator:verify
~/.gradle/caches/modules-2/files-2.1/com.example.samples/libTest/1.2/3c6031a8f092ea73b276aaecd097d5cdd310e62b/libTest
~/.gradle/caches/modules-2/files-2.1/com.example.samples/appTest_macos_x86-64/1.2/e17b194d5ac1d00ad20615197804657d989475bf/appTest

BUILD SUCCESSFUL
```