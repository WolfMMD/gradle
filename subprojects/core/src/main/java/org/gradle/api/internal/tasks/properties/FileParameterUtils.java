/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.properties;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionLeafVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.tasks.PropertyFileCollection;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.MutableBoolean;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.fingerprint.AbsolutePathInputNormalizer;
import org.gradle.internal.fingerprint.IgnoredPathInputNormalizer;
import org.gradle.internal.fingerprint.NameOnlyInputNormalizer;
import org.gradle.internal.fingerprint.RelativePathInputNormalizer;
import org.gradle.util.DeferredUtil;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class FileParameterUtils {

    public static Class<? extends FileNormalizer> determineNormalizerForPathSensitivity(PathSensitivity pathSensitivity) {
        switch (pathSensitivity) {
            case NONE:
                return IgnoredPathInputNormalizer.class;
            case NAME_ONLY:
                return NameOnlyInputNormalizer.class;
            case RELATIVE:
                return RelativePathInputNormalizer.class;
            case ABSOLUTE:
                return AbsolutePathInputNormalizer.class;
            default:
                throw new IllegalArgumentException("Unknown path sensitivity: " + pathSensitivity);
        }
    }

    /**
     * Collects property specs in a sorted set to ensure consistent ordering.
     *
     * @throws IllegalArgumentException if there are multiple properties declared with the same name.
     */
    public static <T extends FilePropertySpec> ImmutableSortedSet<T> collectFileProperties(String displayName, Iterator<? extends T> fileProperties) {
        Set<String> names = Sets.newHashSet();
        ImmutableSortedSet.Builder<T> builder = ImmutableSortedSet.naturalOrder();
        while (fileProperties.hasNext()) {
            T propertySpec = fileProperties.next();
            String propertyName = propertySpec.getPropertyName();
            if (!names.add(propertyName)) {
                throw new IllegalArgumentException(String.format("Multiple %s file properties with name '%s'", displayName, propertyName));
            }
            builder.add(propertySpec);
        }
        return builder.build();
    }

    /**
     * Resolves the actual value for an input file.
     *
     * The value is the file tree rooted at the provided path for an input directory, and the provided path otherwise.
     */
    public static FileCollection resolveInputFileValue(FileCollectionFactory fileCollectionFactory, InputFilePropertyType inputFilePropertyType, Object path) {
        if (inputFilePropertyType == InputFilePropertyType.DIRECTORY) {
            return fileCollectionFactory.resolving(path).getAsFileTree();
        } else {
            return fileCollectionFactory.resolving(path);
        }
    }

    /**
     * Resolves the given output file property to individual property specs.
     *
     * Especially, values of type {@link Map} are resolved.
     */
    public static void resolveOutputFilePropertySpecs(String ownerDisplayName, String propertyName, PropertyValue value, OutputFilePropertyType filePropertyType, FileCollectionFactory fileCollectionFactory, Consumer<OutputFilePropertySpec> consumer) {
        Object unpackedValue = DeferredUtil.unpack(value);
        if (unpackedValue == null) {
            return;
        }
        if (filePropertyType == OutputFilePropertyType.DIRECTORIES || filePropertyType == OutputFilePropertyType.FILES) {
            resolveCompositeOutputFilePropertySpecs(ownerDisplayName, propertyName, unpackedValue, filePropertyType.getOutputType(), fileCollectionFactory, consumer);
        } else {
            FileCollection outputFiles = fileCollectionFactory.resolving(unpackedValue);
            DefaultCacheableOutputFilePropertySpec filePropertySpec = new DefaultCacheableOutputFilePropertySpec(propertyName, null, outputFiles, filePropertyType.getOutputType());
            consumer.accept(filePropertySpec);
        }
    }

    private static void resolveCompositeOutputFilePropertySpecs(final String ownerDisplayName, final String propertyName, Object unpackedValue, final TreeType outputType, FileCollectionFactory fileCollectionFactory, Consumer<OutputFilePropertySpec> consumer) {
        if (unpackedValue instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) unpackedValue).entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException(String.format("Mapped output property '%s' has null key", propertyName));
                }
                String id = key.toString();
                FileCollection outputFiles = fileCollectionFactory.resolving(entry.getValue());
                consumer.accept(new DefaultCacheableOutputFilePropertySpec(propertyName, "." + id, outputFiles, outputType));
            }
        } else {
            final List<File> roots = Lists.newArrayList();
            final MutableBoolean nonFileRoot = new MutableBoolean();
            FileCollectionInternal outputFileCollection = fileCollectionFactory.resolving(unpackedValue);
            outputFileCollection.visitLeafCollections(new FileCollectionLeafVisitor() {
                @Override
                public void visitCollection(FileCollectionInternal fileCollection) {
                    Iterables.addAll(roots, fileCollection);
                }

                @Override
                public void visitGenericFileTree(FileTreeInternal fileTree) {
                    nonFileRoot.set(true);
                }

                @Override
                public void visitFileTree(File root, PatternSet patterns) {
                    // We could support an unfiltered DirectoryFileTree here as a cacheable root,
                    // but because @OutputDirectory also doesn't support it we choose not to.
                    nonFileRoot.set(true);
                }
            });

            if (nonFileRoot.get()) {
                consumer.accept(new CompositeOutputFilePropertySpec(
                    propertyName,
                    new PropertyFileCollection(ownerDisplayName, propertyName, "output", fileCollectionFactory.resolving(unpackedValue)),
                    outputType)
                );
            } else {
                int index = 0;
                for (File root : roots) {
                    FileCollectionInternal outputFiles = fileCollectionFactory.fixed(root);
                    consumer.accept(new DefaultCacheableOutputFilePropertySpec(propertyName, "$" + (++index), outputFiles, outputType));
                }
            }
        }
    }
}
