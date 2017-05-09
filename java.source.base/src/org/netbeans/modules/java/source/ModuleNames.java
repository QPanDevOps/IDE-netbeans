/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 *
 * Contributor(s):
 */
package org.netbeans.modules.java.source;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTaskImpl;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.annotations.common.NullAllowed;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.queries.BinaryForSourceQuery;
import org.netbeans.api.java.queries.SourceForBinaryQuery;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.classfile.ClassFile;
import org.netbeans.modules.classfile.Module;
import org.netbeans.modules.java.source.indexing.JavaIndex;
import org.netbeans.modules.java.source.parsing.FileObjects;
import org.netbeans.modules.java.source.parsing.JavacParser;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.URLMapper;
import org.openide.util.Exceptions;
import org.openide.util.WeakListeners;

/**
 * Computes module names.
 * Computes and caches module names.
 * @author Tomas Zezula
 */
public final class ModuleNames {
    private static final Logger LOG = Logger.getLogger(ModuleNames.class.getName());
    private static final java.util.regex.Pattern AUTO_NAME_PATTERN = java.util.regex.Pattern.compile("-(\\d+(\\.|$))"); //NOI18N
    private static final String RES_MANIFEST = "META-INF/MANIFEST.MF";              //NOI18N
    private static final String ATTR_AUTOMATIC_MOD_NAME = "Automatic-Module-Name";   //NOI18N
    private static final ModuleNames INSTANCE = new ModuleNames();

    private final Map<URL,CacheLine> cache;

    private ModuleNames() {
        this.cache = new ConcurrentHashMap<>();
    }

    @CheckForNull
    public String getModuleName(
            @NonNull final URL rootUrl,
            final boolean canUseSources) {
        try {
            final CacheLine cl = cache.get(rootUrl);
            if (cl != null) {
                return cl.getValue();
            }
        } catch (InvalidCacheLine icl) {
            //pass and recompute
        }
        LOG.log(Level.FINE, "No cache for: {0}", rootUrl);
        if (FileObjects.PROTO_NBJRT.equals(rootUrl.getProtocol())) {
            //Platform
            final String path = rootUrl.getPath();
            int endIndex = path.length() - 1;
            int startIndex = path.lastIndexOf('/', endIndex - 1);   //NOI18N
            return register(
                    rootUrl,
                    new CacheLine(rootUrl, path.substring(startIndex+1, endIndex)));
        }
        final URL srcRootURL = JavaIndex.getSourceRootForClassFolder(rootUrl);
        if (srcRootURL != null) {
            //Cache folder
            return register(rootUrl, getProjectModuleName(rootUrl, Collections.singletonList(srcRootURL), canUseSources));
        }
        final SourceForBinaryQuery.Result2 sfbqRes = SourceForBinaryQuery.findSourceRoots2(rootUrl);
        if (sfbqRes.preferSources()) {
            //Project binary
            final CacheLine cl = getProjectModuleName(
                    rootUrl,
                    Arrays.stream(sfbqRes.getRoots()).map(FileObject::toURL).collect(Collectors.toList()),
                    canUseSources);
            if (cl.getValueNoCheck() != null) {
                return register(rootUrl, cl);
            }
        }
        //Binary
        if (FileUtil.isArchiveArtifact(rootUrl)) {
            //Archive
            final FileObject root = URLMapper.findFileObject(rootUrl);
            if (root != null) {
                final FileObject file = FileUtil.getArchiveFile(root);
                final FileObject moduleInfo = root.getFileObject(FileObjects.MODULE_INFO, FileObjects.CLASS);
                if (moduleInfo != null) {
                    try {
                        final String modName = readModuleName(moduleInfo);
                        final File path = Optional.ofNullable(file)
                                .map(FileUtil::toFile)
                                .orElse(null);
                        return register(
                                rootUrl,
                                path != null ?
                                    new FileCacheLine(rootUrl, modName, path):
                                    new FileObjectCacheLine(rootUrl, modName, moduleInfo));
                    } catch (IOException ioe) {
                        //Behave as javac: Pass to automatic module
                    }
                }
                final FileObject manifest = root.getFileObject(RES_MANIFEST);
                if (manifest != null) {
                    try {
                        try (final InputStream in = new BufferedInputStream(manifest.getInputStream())) {
                            final Manifest mf = new Manifest(in);
                            final String autoModName = mf.getMainAttributes().getValue(ATTR_AUTOMATIC_MOD_NAME);
                            if (autoModName != null) {
                                final File path = Optional.ofNullable(file)
                                        .map(FileUtil::toFile)
                                        .orElse(null);
                                return register(
                                    rootUrl,
                                    path != null ?
                                        new FileCacheLine(rootUrl, autoModName, path):
                                        new FileObjectCacheLine(
                                                rootUrl,
                                                autoModName,
                                                file != null ?
                                                        file :
                                                        manifest));
                            }
                        }
                    } catch (IOException ioe) {
                        //Behave as javac: Pass to automatic module
                    }
                }
                //Automatic module
                if (file != null) {
                    final String modName = autoName(file.getName());
                    final File path = FileUtil.toFile(file);
                    return register(
                            rootUrl,
                            path != null ?
                                new FileCacheLine(rootUrl, modName, path):
                                new FileObjectCacheLine(rootUrl, modName, file));
                }
            }
        } else {
            //Regular module folder or folder
            final FileObject root = URLMapper.findFileObject(rootUrl);
            FileObject moduleInfo;
            if (root != null && (moduleInfo = root.getFileObject(FileObjects.MODULE_INFO, FileObjects.CLASS)) != null) {
                try {
                    final String modName = readModuleName(moduleInfo);
                    final File path = FileUtil.toFile(moduleInfo);
                    return register(
                            rootUrl,
                            path != null ?
                                    new FileCacheLine(rootUrl, modName, path):
                                    new FileObjectCacheLine(rootUrl, modName, moduleInfo));
                } catch (IOException ioe) {
                    //pass to null
                }
            }
        }
        return null;
    }

    public void reset(@NonNull final URL binRootURL) {
        Optional.ofNullable(cache.get(binRootURL))
                .ifPresent(CacheLine::invalidate);
    }

    private String register(
            @NonNull final URL rootUrl,
            @NonNull final CacheLine cacheLine) {
            cache.put(rootUrl, cacheLine);
            return cacheLine.getValueNoCheck();
    }

    @NonNull
    private static CacheLine getProjectModuleName(
            @NonNull final URL artefact,
            @NonNull final List<URL> srcRootURLs,
            final boolean canUseSources) {
        if (srcRootURLs.isEmpty()) {
            return new CacheLine(artefact, null);
        }
        if (srcRootURLs.stream().allMatch((srcRootURL)->JavaIndex.hasSourceCache(srcRootURL,false))) {
            //scanned
            String modName = null;
            for (URL srcRootURL : srcRootURLs) {
                try {
                    modName = JavaIndex.getAttribute(srcRootURL, JavaIndex.ATTR_MODULE_NAME, null);
                    if (modName != null) {
                        break;
                    }
                } catch (IOException ioe) {
                    Exceptions.printStackTrace(ioe);
                }
            }
            if (modName != null) {
                //Has module-info
                return new CacheLine(artefact, modName);
            }
            //No module -> automatic module
            return autoName(artefact, srcRootURLs);
        } else if (canUseSources) {
            FileObject moduleInfo = null;
            FileObject root = null;
            for (URL srcRootUrl : srcRootURLs) {
                final FileObject srcRoot = URLMapper.findFileObject(srcRootUrl);
                if (srcRoot != null) {
                    moduleInfo = srcRoot.getFileObject(FileObjects.MODULE_INFO, FileObjects.JAVA);
                    if (moduleInfo != null) {
                        root = srcRoot;
                        break;
                    }
                }
            }
            if (moduleInfo != null) {
                final String modName = parseModuleName(moduleInfo);
                final File path = FileUtil.toFile(moduleInfo);
                return path != null ?
                        new FileCacheLine(artefact, modName, path):
                        new FileObjectCacheLine(artefact, modName, moduleInfo);
            } else {
                //No module -> automatic module
                return autoName(artefact, srcRootURLs);
            }
        }
        return new CacheLine(artefact, null);
    }

    @CheckForNull
    public static String parseModuleName(
            @NonNull final FileObject moduleInfo) {
        final JavacTaskImpl jt = JavacParser.createJavacTask(
                new ClasspathInfo.Builder(ClassPath.EMPTY).build(),
                null,
                "1.3",  //min sl to prevent validateSourceLevel warning
                null,
                null,
                null,
                null,
                null,
                null);
        try {
            final CompilationUnitTree cu =  jt.parse(FileObjects.fileObjectFileObject(
                    moduleInfo,
                    moduleInfo.getParent(),
                    null,
                    FileEncodingQuery.getEncoding(moduleInfo))).iterator().next();
            final List<? extends Tree> typeDecls = cu.getTypeDecls();
            if (!typeDecls.isEmpty()) {
                final Tree typeDecl = typeDecls.get(0);
                if (typeDecl.getKind() == Tree.Kind.MODULE) {
                    return ((ModuleTree)typeDecl).getName().toString();
                }
            }
        } catch (IOException ioe) {
            Exceptions.printStackTrace(ioe);
        }
        return null;
    }

    @NonNull
    private static CacheLine autoName(
            @NonNull final URL artefact,
            @NonNull final List<? extends URL> srcRootURLs) {
        final BinaryForSourceQuery.Result res = BinaryForSourceQuery.findBinaryRoots(srcRootURLs.get(0));
        for (URL binRoot : res.getRoots()) {
            if (FileObjects.JAR.equals(binRoot.getProtocol())) {
                final String modName = autoName(FileObjects.stripExtension(FileUtil.archiveOrDirForURL(binRoot).getName()));
                return new BinCacheLine(artefact, modName, res);
            }
        }
        return new CacheLine(artefact, null);
    }

    @CheckForNull
    private static String autoName(@NonNull String moduleName) {
        final java.util.regex.Matcher matcher = AUTO_NAME_PATTERN.matcher(moduleName);
        if (matcher.find()) {
            int start = matcher.start();
            moduleName = moduleName.substring(0, start);
        }
        moduleName =  moduleName
            .replaceAll("(\\.|\\d)*$", "")    // remove trailing version
            .replaceAll("[^A-Za-z0-9]", ".")  // replace non-alphanumeric
            .replaceAll("(\\.)(\\1)+", ".")   // collapse repeating dots
            .replaceAll("^\\.", "")           // drop leading dots
            .replaceAll("\\.$", "");          // drop trailing dots
        return moduleName.isEmpty() ?
            null :
            moduleName;
    }

    @CheckForNull
    private static String readModuleName(@NonNull FileObject moduleInfo) throws IOException {
        try (final InputStream in = new BufferedInputStream(moduleInfo.getInputStream())) {
            final ClassFile clz = new ClassFile(in, false);
            final Module modle = clz.getModule();
            return modle != null ?
                    modle.getName() :
                    null;
        }
    }


    @NonNull
    public static ModuleNames getInstance() {
        return INSTANCE;
    }

    private static final class InvalidCacheLine extends Exception {
        static final InvalidCacheLine INSTANCE = new InvalidCacheLine();

        private InvalidCacheLine() {
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static class CacheLine {
        private final URL artefact;
        private final String value;
        private volatile boolean  invalid;

        CacheLine(
                @NonNull final URL artefact,
                @NullAllowed final String value) {
            this.artefact = artefact;
            this.value = value;
            this.invalid = false;
        }

        @CheckForNull
        final String getValue() throws InvalidCacheLine {
            if (invalid) {
                throw InvalidCacheLine.INSTANCE;
            } else {
                return value;
            }
        }

        @CheckForNull
        final String getValueNoCheck() {
            return value;
        }

        void invalidate() {
            LOG.log(Level.FINE, "Invalidated cache for: {0}", artefact);
            this.invalid = true;
        }
    }

    private static final class FileCacheLine extends CacheLine implements FileChangeListener {
        private final File path;
        private final AtomicBoolean listens = new AtomicBoolean(true);
        FileCacheLine(
                @NonNull final URL artefact,
                @NullAllowed final String modName,
                @NonNull final File path) {
            super(artefact, modName);
            this.path = path;
            FileUtil.addFileChangeListener(this, path);
        }

        @Override
        public void fileFolderCreated(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileDataCreated(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileChanged(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileDeleted(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileRenamed(FileRenameEvent fe) {
            invalidate();
        }

        @Override
        public void fileAttributeChanged(FileAttributeEvent fe) {
        }

        @Override
        void invalidate() {
            super.invalidate();
            if (listens.compareAndSet(true, false)) {
                FileUtil.removeFileChangeListener(this, path);
            }
        }
    }

    private static final class FileObjectCacheLine extends CacheLine implements FileChangeListener {
        private final FileObject file;
        private final FileChangeListener wl;

        FileObjectCacheLine(
                @NonNull final URL artefact,
                @NullAllowed final String modName,
                @NonNull final FileObject file) {
            super(artefact, modName);
            this.file = file;
            this.wl = FileUtil.weakFileChangeListener(this, this.file);
            this.file.addFileChangeListener(this.wl);
        }

        @Override
        public void fileFolderCreated(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileDataCreated(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileChanged(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileDeleted(FileEvent fe) {
            invalidate();
        }

        @Override
        public void fileRenamed(FileRenameEvent fe) {
            invalidate();
        }

        @Override
        public void fileAttributeChanged(FileAttributeEvent fe) {
        }

        @Override
        void invalidate() {
            super.invalidate();
            this.file.removeFileChangeListener(this.wl);
        }
    }

    private static final class BinCacheLine extends CacheLine implements ChangeListener {
        private final BinaryForSourceQuery.Result res;
        private final ChangeListener cl;

        BinCacheLine(
                @NonNull final URL artefact,
                @NonNull final String modName,
                @NonNull final BinaryForSourceQuery.Result res) {
            super(artefact, modName);
            this.res =  res;
            this.cl = WeakListeners.change(this, this.res);
            this.res.addChangeListener(cl);
        }

        @Override
        public void stateChanged(ChangeEvent e) {
            invalidate();
        }

        @Override
        void invalidate() {
            super.invalidate();
            this.res.removeChangeListener(this.cl);
        }
    }
}
