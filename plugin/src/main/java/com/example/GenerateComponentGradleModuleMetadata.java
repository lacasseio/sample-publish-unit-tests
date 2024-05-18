package com.example;

//import dev.gradleplugins.grava.publish.metadata.GradleModuleMetadata
//import dev.gradleplugins.grava.publish.metadata.GradleModuleMetadataWriter
//import dev.nokee.platform.base.Variant

import org.gradle.api.DefaultTask;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

abstract class GenerateComponentGradleModuleMetadata extends DefaultTask {
    @Nested
    public abstract NamedDomainObjectContainer<VariantIdentity> getVariants();

    @OutputFile
    public abstract RegularFileProperty getModuleFile();

    @Input
    public abstract Property<String> getGroupId();

    @Input
    public abstract Property<String> getArtifactId();

    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getStatus();

    @Inject
    public GenerateComponentGradleModuleMetadata() {}

    private ModuleMetadataJsonWriter newJsonWriter() throws IOException {
        return new ModuleMetadataJsonWriter(new ScopeAwareJsonWriter(new JsonWriter(Files.newBufferedWriter(getModuleFile().getAsFile().get().toPath()))));
    }

    @TaskAction
    private void doGenerate() throws IOException {
        try (final ModuleMetadataJsonWriter out = newJsonWriter()) {
            out.write();
        }
    }

    // Poor man Gson's JsonWriter
    private static final class JsonWriter implements AutoCloseable {
        private final Deque<Long> stack = new ArrayDeque<>();
        private long count = 0;
        private String deferredName = null;
        private final Writer out;

        public JsonWriter(Writer out) {
            this.out = out;
        }

        private void writeDeferredName() throws IOException {
            writeDelimiter();
            if (deferredName != null) {
                out.write("\"" + deferredName + "\":");
                deferredName = null;
            }
        }

        private void writeDelimiter() throws IOException {
            if (count > 0) {
                out.write(',');
            }
        }

        private long pushCount() {
            stack.push(count);
            return 0;
        }

        private long popCount() {
            return stack.pop() + 1;
        }

        public JsonWriter beginObject() throws IOException {
            writeDeferredName();
            out.write('{');
            count = pushCount();
            return this;
        }
        public JsonWriter endObject() throws IOException {
            out.write('}');
            count = popCount();
            return this;
        }

        public JsonWriter beginArray() throws IOException {
            writeDeferredName();
            out.write('[');
            count = pushCount();
            return this;
        }

        public JsonWriter endArray() throws IOException {
            out.write(']');
            count = popCount();
            return this;
        }

        public JsonWriter name(String name) throws IOException {
            assert deferredName == null;
            deferredName = name;
            return this;
        }

        public JsonWriter value(String value) throws IOException {
            // Warning: double quote in value will make this method fail, hence the assertion
            assert !value.contains("\"") : "double quote not supported in JSON string value";
            writeDeferredName();
            out.write("\"" + value + "\"");
            count++;
            return this;
        }

        public JsonWriter value(boolean value) throws IOException {
            writeDeferredName();
            out.write(String.valueOf(value));
            count++;
            return this;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // Poor man Gradle's JsonWriterScope
    private static final class ScopeAwareJsonWriter implements AutoCloseable {
        private final JsonWriter out;

        private ScopeAwareJsonWriter(JsonWriter out) {
            this.out = out;
        }

        interface Contents {
            void write() throws IOException;
        }

        public void writeObject(Contents contents) throws IOException {
            out.beginObject();
            contents.write();
            out.endObject();
        }

        public void writeObject(String name, Contents contents) throws IOException {
            out.name(name);
            out.beginObject();
            contents.write();
            out.endObject();
        }

        public void writeArray(String name, Contents contents) throws IOException {
            out.name(name);
            out.beginArray();
            contents.write();
            out.endArray();
        }

        public void write(String name, String value) throws IOException {
            out.name(name).value(value);
        }

        public void write(String name, boolean value) throws IOException {
            out.name(name).value(value);
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // Poor man Gradle's ModuleMetadataJsonWriter
    //   It is not meant to be a full feature writer for the module metadata, but just enough for what is required.
    private final class ModuleMetadataJsonWriter implements AutoCloseable {
        private final ScopeAwareJsonWriter out;

        private ModuleMetadataJsonWriter(ScopeAwareJsonWriter out) {
            this.out = out;
        }

        public void write() throws IOException {
            out.writeObject(() -> {
                writeFormat();
                writeIdentity();
                writeCreator();
                writeVariants();
            });
        }

        private void writeFormat() throws IOException {
            out.write("formatVersion", "1.1");
        }

        private void writeIdentity() throws IOException {
            out.writeObject("component", () -> {
                out.write("group", getGroupId().get());
                out.write("module", getArtifactId().get());
                out.write("version", getVersion().get());
                out.writeObject("attributes", () -> {
                    out.write("org.gradle.status", getStatus().get());
                });
            });
        }

        private void writeCreator() throws IOException {
            // In fact, this module is created by this sample, not Gradle.
            out.writeObject("createdBy", () -> {
                out.writeObject("gradle", () -> {
                    out.write("version", GradleVersion.current().getVersion());
                });
            });
        }

        private void writeVariants() throws IOException {
            if (getVariants().isEmpty()) {
                return; // no variant to glue together
            }
            out.writeArray("variants", () -> {
                for (VariantIdentity variant : getVariants()) {
                    out.writeObject(() -> {
                        out.write("name", variant.getName());
                        writeAttributes(variant);
                        writeAvailableAt(variant);
                    });
                }
            });
        }

        private void writeAttributes(VariantIdentity variant) throws IOException {
            out.writeObject("attributes", () -> {
                for (VariantIdentity.VariantAttribute attribute : variant.getAttributes()) {
                    writeAttribute(attribute.getKey().get(), attribute.getValue().get());
                }
            });
        }

        private void writeAttribute(Attribute<?> key, Object value) throws IOException {
            if (key.getType().equals(String.class)) {
                assert value instanceof String;
                out.write(key.getName(), value.toString());
            } else if (Named.class.isAssignableFrom(key.getType())) {
                assert value instanceof Named;
                out.write(key.getName(), ((Named) value).getName());
            } else if (key.getType().equals(Boolean.class)) {
                assert value instanceof Boolean;
                out.write(key.getName(), ((Boolean) value).booleanValue());
            } else if (Enum.class.isAssignableFrom(key.getType())) {
                assert value instanceof Enum;
                out.write(key.getName(), ((Enum<?>) value).name());
            } else {
                throw new UnsupportedOperationException("cannot write attribute of type '" + key.getType().getSimpleName() + "'");
            }
        }

        private void writeAvailableAt(VariantIdentity variant) throws IOException {
            out.writeObject("available-at", () -> {
                out.write("url", String.format("../../%1$s/%2$s/%1$s-%2$s.module", variant.getArtifactId().get(), getVersion().get()));
                out.write("group", getGroupId().get());
                out.write("module", variant.getArtifactId().get());
                out.write("version", getVersion().get());
            });
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    public static abstract class VariantIdentity implements Named {
        private final String name;
        private final ObjectFactory objects;

        @Inject
        public VariantIdentity(String name, ObjectFactory objects) {
            this.name = name;
            this.objects = objects;
        }

        @Input
        @Override
        public String getName() {
            return name;
        }

        @Nested
        public abstract DomainObjectSet<VariantAttribute> getAttributes();

        public <T> void attribute(Attribute<T> attribute, T value) {
            VariantAttribute result = objects.newInstance(VariantAttribute.class);
            result.getKey().value(attribute).disallowChanges();
            result.getValue().value(value).disallowChanges();
            getAttributes().add(result);
        }

        public interface VariantAttribute {
            @Input
            Property<Attribute<?>> getKey();

            @Input
            Property<Object> getValue();
        }

        @Input
        public abstract Property<String> getArtifactId();
    }
}