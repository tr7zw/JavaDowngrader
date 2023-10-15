/*
 * This file is part of JavaDowngrader - https://github.com/RaphiMC/JavaDowngrader
 * Copyright (C) 2023 RK_01/RaphiMC and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.javadowngrader.coveragescanner;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;

public class CoverageScanner implements Closeable {
    @Nullable
    private final CtSym ct;

    private final Map<String, Integer> classVersionCache = new HashMap<>();
    private final Map<String, URL> miscClassUrlCache = new HashMap<>();

    public CoverageScanner(@Nullable Path ctSymPath) throws IOException {
        ct = ctSymPath != null ? CtSym.open(ctSymPath) : null;
    }

    @SuppressWarnings("resource") // Handled by iterStream
    public void scanJar(Path jarPath, ScanHandler handler, @Nullable Integer baseJava) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, null)) {
            final Path root = fs.getRootDirectories().iterator().next();
            IOUtil.iterStream(
                Files.walk(root)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(Files::isRegularFile),
                path -> scanClass(path, handler, baseJava)
            );
        }
    }

    public void scanClass(Path classFilePath, ScanHandler handler, @Nullable Integer baseJava) throws IOException {
        final ClassReader reader;
        try (InputStream is = Files.newInputStream(classFilePath)) {
            reader = new ClassReader(is);
        }
        scanClass(reader, handler, baseJava);
    }

    public void scanClass(ClassReader reader, ScanHandler handler, @Nullable Integer baseJava) {
        final int javaVersion = baseJava != null
            ? baseJava
            : reader.readInt(reader.getItem(1) - 7) - 44; // Match ClassReader.accept
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            MethodLocation classLocation;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                classLocation = new MethodLocation(name.replace('/', '.'), null, javaVersion);
                checkSignature(classLocation, handler, signature, false);
                if (superName != null) {
                    checkType(classLocation, handler, Type.getObjectType(superName));
                }
                if (interfaces != null) {
                    for (final String intf : interfaces) {
                        checkType(classLocation, handler, Type.getObjectType(intf));
                    }
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return checkAnnotation(classLocation, handler, descriptor, visible);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                checkType(classLocation, handler, Type.getObjectType(name));
                if (outerName != null) {
                    checkType(classLocation, handler, Type.getObjectType(outerName));
                }
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                return checkAnnotation(classLocation, handler, descriptor, visible);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                checkType(classLocation, handler, Type.getType(descriptor));
                checkSignature(classLocation, handler, signature, true);
                return new FieldVisitor(api) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return checkAnnotation(classLocation, handler, descriptor, visible);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                        return checkAnnotation(classLocation, handler, descriptor, visible);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                final MethodLocation methodLocation = new MethodLocation(classLocation.inClass, name, classLocation.inJava);
                checkType(methodLocation, handler, Type.getType(descriptor));
                checkSignature(methodLocation, handler, signature, false);
                if (exceptions != null) {
                    for (final String exc : exceptions) {
                        checkType(methodLocation, handler, Type.getObjectType(exc));
                    }
                }
                // TODO
                return null;
            }

            @Override
            public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                checkType(classLocation, handler, Type.getType(descriptor));
                checkSignature(classLocation, handler, signature, false);
                return new RecordComponentVisitor(api) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return checkAnnotation(classLocation, handler, descriptor, visible);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                        return checkAnnotation(classLocation, handler, descriptor, visible);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private void checkSignature(MethodLocation location, ScanHandler handler, String signature, boolean isField) {
        if (signature == null) return;
        final SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                checkType(location, handler, Type.getObjectType(name));
            }
        };
        if (isField) {
            new SignatureReader(signature).acceptType(visitor);
        } else {
            new SignatureReader(signature).accept(visitor);
        }
    }

    private AnnotationVisitor checkAnnotation(
        MethodLocation location, ScanHandler handler, String descriptor, boolean visible
    ) {
        if (!visible) {
            // Invisible annotations can be in the class file without issue
            return null;
        }
        checkType(location, handler, Type.getType(descriptor));
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                checkObject(location, handler, value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                checkType(location, handler, Type.getType(descriptor));
                return this;
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                final Type type = Type.getType(descriptor);
                checkType(location, handler, type);
                // TODO: Check field
            }
        };
    }

    private void checkObject(MethodLocation location, ScanHandler handler, Object obj) {
        if (obj instanceof Handle) {
            final Handle handle = (Handle)obj;
            checkType(location, handler, Type.getObjectType(handle.getOwner()));
            checkType(location, handler, Type.getType(handle.getDesc()));
        }
        if (obj instanceof ConstantDynamic) {
            final ConstantDynamic condy = (ConstantDynamic)obj;
            checkType(location, handler, Type.getType(condy.getDescriptor()));
            checkObject(location, handler, condy.getBootstrapMethod());
        }
    }

    private void checkType(MethodLocation location, ScanHandler handler, Type type) {
        if (type.getSort() == Type.METHOD) {
            for (final Type arg : type.getArgumentTypes()) {
                checkType(location, handler, arg);
            }
            checkType(location, handler, type.getReturnType());
            return;
        }
        if (type.getSort() == Type.ARRAY) {
            checkType(location, handler, type.getElementType());
            return;
        }
        if (type.getSort() != Type.OBJECT) return;

        final String className = type.getClassName();

        final Integer cached = classVersionCache.get(className);
        if (cached != null) {
            if (cached > location.inJava) {
                handler.missing(location, new MethodLocation(className, null, cached));
            }
            return;
        }

        if (ct != null) {
            final SortedMap<Integer, Path> versions = ct.getVersions(className);
            if (versions != null) {
                final int minVersion = versions.firstKey();
                classVersionCache.put(className, minVersion);
                if (minVersion > location.inJava) {
                    handler.missing(location, new MethodLocation(className, null, minVersion));
                }
                return;
            }
        }

        if (!className.startsWith("java.") && !className.startsWith("jdk.")) return;
        if (miscClassUrlCache.containsKey(className)) return;

        final URL classUrl = ClassLoader.getSystemResource(className.replace('.', '/').concat(".class"));
        if (classUrl == null) {
            handler.missing(location, new MethodLocation(className, null, 0));
        } else {
            classVersionCache.put(className, 0);
            miscClassUrlCache.put(className, classUrl);
        }
    }

    private void checkField(MethodLocation location, ScanHandler handler, String owner, String name, String descriptor) {
        final Type ownerType = Type.getObjectType(owner);
        checkType(location, handler, ownerType);
        // No need to check descriptor

        final String className = ownerType.getClassName();
        final int addedVersion = classVersionCache.get(className);
        if (addedVersion > location.inJava) return;
        // TODO
    }

    @Override
    public void close() throws IOException {
        if (ct != null) {
            ct.close();
        }
    }

    @FunctionalInterface
    public interface ScanHandler {
        void missing(MethodLocation location, MethodLocation missing);
    }

    public static class MethodLocation {
        private final String inClass;
        @Nullable
        private final String inMethod;
        private final int inJava;

        public MethodLocation(String inClass, @Nullable String inMethod, int inJava) {
            this.inClass = inClass;
            this.inMethod = inMethod;
            this.inJava = inJava;
        }

        public String getInClass() {
            return inClass;
        }

        @Nullable
        public String getInMethod() {
            return inMethod;
        }

        public int getInJava() {
            return inJava;
        }

        @Override
        public String toString() {
            return "MethodLocation{" +
                "inClass='" + inClass + '\'' +
                ", inMethod='" + inMethod + '\'' +
                ", inJava=" + inJava +
                '}';
        }
    }
}
