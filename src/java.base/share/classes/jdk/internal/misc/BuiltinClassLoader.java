/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.misc;

import java.io.File;
import java.io.FilePermission;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleReader;
import java.lang.reflect.Module;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import sun.misc.URLClassPath;
import sun.misc.Resource;
import sun.misc.VM;


/**
 * The extension or application class loader. Resources loaded from modules
 * defined to the boot class loader are also loaded via an instance of this
 * ClassLoader type.
 *
 * <p> This ClassLoader supports loading of classes and resources from modules.
 * Modules are defined to the ClassLoader by invoking the {@link #loadModule}
 * method. Defining a module to this ClassLoader has the effect of making the
 * types in the module visible. </p>
 *
 * <p> This ClassLoader also supports loading of classes and resources from a
 * class path of URLs that are specified to the ClassLoader at construction
 * time. The class path may expand at runtime (the Class-Path attribute in JAR
 * files or via instrumentation agents). </p>
 *
 * <p> The delegation model used by this ClassLoader differs to the regular
 * delegation model. When requested to load a class then this ClassLoader first
 * maps the class name to its package name. If there is a module defined to a
 * BuiltinClassLoader containing this package then the class loader delegates
 * directly to that class loader. If there isn't a module containing the
 * package then it delegates the search to the parent class loader and if not
 * found in the parent then it searches the class path. The main difference
 * between this and the usual delegation model is that it allows the extension
 * class loader to delegate to the application class loader, important with
 * upgraded modules defined to the extension class loader.
 */

public class BuiltinClassLoader
    extends SecureClassLoader
{

    static {
        ClassLoader.registerAsParallelCapable();
    }

    // parent ClassLoader
    private final BuiltinClassLoader parent;

    // -Xpatch directories, can be empty
    private final List<Path> patchDirs;

    // the URL class path or null if there is no class path
    private final URLClassPath ucp;


    /**
     * A module defined/loaded by a built-in class loader.
     *
     * A LoadedModule encapsulates a ModuleReference along with its location in
     * URL form, which is needed to avoid invocations of {@link
     * java.net.URI#toURL() toURL()} and the loading of protocol handlers when
     * defining classes or packages.
     */
    private static class LoadedModule {
        private final BuiltinClassLoader loader;
        private final ModuleReference mref;
        private final URL url;          // may be null

        LoadedModule(BuiltinClassLoader loader, ModuleReference mref) {
            URL url = null;
            if (mref.location().isPresent()) {
                try {
                    url = mref.location().get().toURL();
                } catch (MalformedURLException e) { }
            }
            this.loader = loader;
            this.mref = mref;
            this.url = url;
        }

        BuiltinClassLoader loader() { return loader; }
        ModuleReference mref() { return mref; }
        URL location() { return url; }
    }


    // maps package name to loaded module for modules in the boot layer
    private static final Map<String, LoadedModule> packageToModule
        = new ConcurrentHashMap<>(1024);

    // maps a module name to a module reference
    private final Map<String, ModuleReference> nameToModule;

    // maps a module reference to a module reader
    private final Map<ModuleReference, ModuleReader> moduleToReader;


    /**
     * Create a new instance.
     */
    BuiltinClassLoader(BuiltinClassLoader parent,
                       List<Path> patchDirs,
                       URLClassPath ucp)
    {
        // ensure getParent() returns null when the parent is the boot loader
        super(parent == null || parent == ClassLoaders.bootLoader() ? null : parent);

        this.parent = parent;
        this.patchDirs = patchDirs;
        this.ucp = ucp;

        this.nameToModule = new ConcurrentHashMap<>();
        this.moduleToReader = new ConcurrentHashMap<>();
    }

    /**
     * Register a module this this class loader. This has the effect of making
     * the types in the module visible.
     */
    public void loadModule(ModuleReference mref) {
        String mn = mref.descriptor().name();
        if (nameToModule.putIfAbsent(mn, mref) != null) {
            throw new InternalError(mn + " already defined to this loader");
        }

        LoadedModule loadedModule = new LoadedModule(this, mref);
        for (String pn : mref.descriptor().packages()) {
            LoadedModule other = packageToModule.putIfAbsent(pn, loadedModule);
            if (other != null) {
                throw new InternalError(pn + " in modules " + mn + " and "
                        + other.mref().descriptor().name());
            }
        }
    }

    // -- finding resources

    /**
     * Returns a URL to a resource of the given name in a module defined to
     * this class loader.
     */
    @Override
    public URL findResource(String mn, String name) throws IOException {
        ModuleReference mref = nameToModule.get(mn);
        if (mref == null)
            return null;   // not defined to this class loader

        URL url;

        try {
            url = AccessController.doPrivileged(
                new PrivilegedExceptionAction<URL>() {
                    @Override
                    public URL run() throws IOException {
                        URI u = moduleReaderFor(mref).find(name).orElse(null);
                        if (u != null) {
                            try {
                                return u.toURL();
                            } catch (MalformedURLException e) { }
                        }
                        return null;
                    }
                });
        } catch (PrivilegedActionException pae) {
            throw (IOException) pae.getCause();
        }

        // check access to the URL
        return checkURL(url);
    }

    /**
     * Returns an input stream to a resource of the given name in a module
     * defined to this class loader.
     */
    public InputStream findResourceAsStream(String mn, String name)
        throws IOException
    {
        // Need URL to resource when running with a security manager so that
        // the right permission check is done.
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {

            URL url = findResource(mn, name);
            return (url != null) ? url.openStream() : null;

        } else {

            ModuleReference mref = nameToModule.get(mn);
            if (mref == null)
                return null;   // not defined to this class loader

            try {
                return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<InputStream>() {
                        @Override
                        public InputStream run() throws IOException {
                            return moduleReaderFor(mref).open(name).orElse(null);
                        }
                    });
            } catch (PrivilegedActionException pae) {
                throw (IOException) pae.getCause();
            }
        }
    }

    /**
     * Finds the resource with the given name on the class path of this class
     * loader.
     */
    @Override
    public URL findResource(String name) {
        if (ucp != null) {
            PrivilegedAction<URL> pa = () -> ucp.findResource(name, false);
            URL url = AccessController.doPrivileged(pa);
            return checkURL(url);
        } else {
            return null;
        }
    }

    /**
     * Returns an enumeration of URL objects to all the resources with the
     * given name on the class path of this class loader.
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        if (ucp != null) {
            List<URL> result = new ArrayList<>();
            PrivilegedAction<Enumeration<URL>> pa = () -> ucp.findResources(name, false);
            Enumeration<URL> e = AccessController.doPrivileged(pa);
            while (e.hasMoreElements()) {
                URL url = checkURL(e.nextElement());
                if (url != null) {
                    result.add(url);
                }
            }
            return Collections.enumeration(result); // checked URLs
        } else {
            return Collections.emptyEnumeration();
        }
    }

    // -- finding/loading classes

    /**
     * Finds the class with the specified binary name.
     */
    @Override
    protected Class<?> findClass(String cn) throws ClassNotFoundException {
        // no class loading until VM is fully initialized
        if (!VM.isModuleSystemInited())
            throw new ClassNotFoundException(cn);

        // find the candidate module for this class
        LoadedModule loadedModule = findModule(cn);

        Class<?> c = null;
        if (loadedModule != null) {

            // attempt to load class in module defined to this loader
            if (loadedModule.loader() == this) {
                c = findClassInModuleOrNull(loadedModule, cn);
            }

        } else {

            // search class path
            if (ucp != null) {
                c = findClassOnClassPathOrNull(cn);
            }

        }

        // not found
        if (c == null)
            throw new ClassNotFoundException(cn);

        return c;
    }

    /**
     * Loads the class with the specified binary name.
     */
    @Override
    protected Class<?> loadClass(String cn, boolean resolve)
        throws ClassNotFoundException
    {
        Class<?> c = loadClassOrNull(cn, resolve);
        if (c == null)
            throw new ClassNotFoundException(cn);
        return c;
    }

    /**
     * A variation of {@code loadCass} to load a class with the specified
     * binary name. This method returns {@code null} when the class is not
     * found.
     */
    protected Class<?> loadClassOrNull(String cn, boolean resolve) {
        synchronized (getClassLoadingLock(cn)) {
            // check if already loaded
            Class<?> c = findLoadedClass(cn);

            if (c == null) {

                // find the candidate module for this class
                LoadedModule loadedModule = findModule(cn);
                if (loadedModule != null) {

                    // package is in a module
                    BuiltinClassLoader loader = loadedModule.loader();
                    if (loader == this) {
                        if (VM.isModuleSystemInited()) {
                            c = findClassInModuleOrNull(loadedModule, cn);
                        }
                    } else {
                        // delegate to the other loader
                        c = loader.loadClassOrNull(cn);
                    }

                } else {

                    // check parent
                    if (parent != null) {
                        c = parent.loadClassOrNull(cn);
                    }

                    // check class path
                    if (c == null && ucp != null && VM.isModuleSystemInited()) {
                        c = findClassOnClassPathOrNull(cn);
                    }
                }

            }

            if (resolve && c != null)
                resolveClass(c);

            return c;
        }
    }

    /**
     * A variation of {@code loadCass} to load a class with the specified
     * binary name. This method returns {@code null} when the class is not
     * found.
     */
    protected  Class<?> loadClassOrNull(String cn) {
        return loadClassOrNull(cn, false);
    }

    /**
     * Find the candidate loaded module for the given class name.
     * Returns {@code null} if none of the modules defined to this
     * class loader contain the API package for the class.
     */
    private LoadedModule findModule(String cn) {
        int pos = cn.lastIndexOf('.');
        if (pos < 0)
            return null; // unnamed package

        String pn = cn.substring(0, pos);
        return packageToModule.get(pn);
    }

    /**
     * Finds the class with the specified binary name if in a module
     * defined to this ClassLoader.
     *
     * @return the resulting Class or {@code null} if not found
     */
    private Class<?> findClassInModuleOrNull(LoadedModule loadedModule, String cn) {
        PrivilegedAction<Class<?>> pa = () -> defineClass(cn, loadedModule);
        return AccessController.doPrivileged(pa);
    }

    /**
     * Finds the class with the specified binary name on the class path.
     *
     * @return the resulting Class or {@code null} if not found
     */
    private Class<?> findClassOnClassPathOrNull(String cn) {
        return AccessController.doPrivileged(
            new PrivilegedAction<Class<?>>() {
                public Class<?> run() {
                    String path = cn.replace('.', '/').concat(".class");
                    Resource res = ucp.getResource(path, false);
                    if (res != null) {
                        try {
                            return defineClass(cn, res);
                        } catch (IOException ioe) {
                            // TBD on how I/O errors should be propagated
                        }
                    }
                    return null;
                }
            });
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes from the given module.
     *
     * @return the resulting Class or {@code null} if an I/O error occurs
     */
    private Class<?> defineClass(String cn, LoadedModule loadedModule) {
        ModuleReference mref = loadedModule.mref();
        ModuleReader reader = moduleReaderFor(mref);

        try {
            // read class file
            String rn = cn.replace('.', '/').concat(".class");
            ByteBuffer bb = reader.read(rn).orElse(null);
            if (bb == null) {
                // class not found
                return null;
            }

            try {

                // define a package in the named module
                int pos = cn.lastIndexOf('.');
                String pn = cn.substring(0, pos);
                if (getDefinedPackage(pn) == null) {
                    definePackage(pn, loadedModule);
                }

                // define class to VM
                URL url = loadedModule.location();
                CodeSource cs = new CodeSource(url, (CodeSigner[]) null);
                return defineClass(cn, bb, cs);

            } finally {
                reader.release(bb);
            }

        } catch (IOException ioe) {
            // TBD on how I/O errors should be propagated
            return null;
        }
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes via the given Resource object.
     *
     * @return the resulting Class
     * @throws IOException if reading the resource fails
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    private Class<?> defineClass(String cn, Resource res) throws IOException {
        URL url = res.getCodeSourceURL();

        // if class is in a named package then ensure that the package is defined
        int pos = cn.lastIndexOf('.');
        if (pos != -1) {
            String pn = cn.substring(0, pos);
            Manifest man = res.getManifest();
            defineOrCheckPackage(pn, man, url);
        }

        // defines the class to the runtime
        ByteBuffer bb = res.getByteBuffer();
        if (bb != null) {
            CodeSigner[] signers = res.getCodeSigners();
            CodeSource cs = new CodeSource(url, signers);
            return defineClass(cn, bb, cs);
        } else {
            byte[] b = res.getBytes();
            CodeSigner[] signers = res.getCodeSigners();
            CodeSource cs = new CodeSource(url, signers);
            return defineClass(cn, b, 0, b.length, cs);
        }
    }


    // -- packages

    /**
     * Define a Package this to this class loader if not already defined.
     * If the package name is in a module defined to this class loader then
     * the resulting Package is sealed with the code source that is the
     * module location.
     *
     * @param pn package name
     */
    Package definePackage(String pn) {
        Package pkg = getDefinedPackage(pn);
        if (pkg == null) {
            LoadedModule loadedModule = packageToModule.get(pn);
            if (loadedModule == null || loadedModule.loader() != this) {
                pkg = definePackage(pn, null, null, null, null, null, null, null);
            } else {
                pkg = definePackage(pn, loadedModule);
            }
        }
        return pkg;
    }

    /**
     * Define a package for the given class to this class loader, if not
     * already defined.
     *
     * @param c a Class defined by this class loader
     */
    Package definePackage(Class<?> c) {
        Module m = c.getModule();
        String cn = c.getName();
        int pos = cn.lastIndexOf('.');
        if (pos < 0 && m.isNamed()) {
            throw new InternalError("unnamed package in named module "
                                    + m.getName());
        }
        String pn = (pos != -1) ? cn.substring(0, pos) : "";
        Package p = getDefinedPackage(pn);

        if (p == null) {
            URL url = null;

            // The given class may be dynamically generated and
            // its package is not in packageToModule map.
            if (m.isNamed()) {
                ModuleReference mref = nameToModule.get(m.getName());
                if (mref != null) {
                    URI uri = mref.location().orElse(null);
                    if (uri != null) {
                        try {
                            url = uri.toURL();
                        } catch (MalformedURLException e) { }
                    }
                }
            }

            p = definePackage(pn, null, null, null, null, null, null, url);
        }
        return p;
    }

    /**
     * Define a Package this to this class loader. The resulting Package
     * is sealed with the code source that is the module location.
     */
    private Package definePackage(String pn, LoadedModule loadedModule) {
        URL url = loadedModule.location();
        return definePackage(pn, null, null, null, null, null, null, url);
    }

    /**
     * Defines a package in this ClassLoader. If the package is already defined
     * then its sealing needs to be checked if sealed by the legacy sealing
     * mechanism.
     *
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    protected Package defineOrCheckPackage(String pn, Manifest man, URL url) {
        Package pkg = getAndVerifyPackage(pn, man, url);
        if (pkg == null) {
            try {
                if (man != null) {
                    pkg = definePackage(pn, man, url);
                } else {
                    pkg = definePackage(pn, null, null, null, null, null, null, null);
                }
            } catch (IllegalArgumentException iae) {
                // defined by another thread so need to re-verify
                pkg = getAndVerifyPackage(pn, man, url);
                if (pkg == null)
                    throw new InternalError("Cannot find package: " + pn);
            }
        }
        return pkg;
    }

    /**
     * Get the Package with the specified package name. If defined
     * then verify that it against the manifest and code source.
     *
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    private Package getAndVerifyPackage(String pn, Manifest man, URL url) {
        Package pkg = getDefinedPackage(pn);
        if (pkg != null) {
            if (pkg.isSealed()) {
                if (!pkg.isSealed(url)) {
                    throw new SecurityException(
                        "sealing violation: package " + pn + " is sealed");
                }
            } else {
                // can't seal package if already defined without sealing
                if ((man != null) && isSealed(pn, man)) {
                    throw new SecurityException(
                        "sealing violation: can't seal package " + pn +
                        ": already defined");
                }
            }
        }
        return pkg;
    }

    /**
     * Defines a new package in this ClassLoader. The attributes in the specified
     * Manifest are use to get the package version and sealing information.
     *
     * @throws IllegalArgumentException if the package name duplicates an
     * existing package either in this class loader or one of its ancestors
     */
    private Package definePackage(String pn, Manifest man, URL url) {
        String specTitle = null;
        String specVersion = null;
        String specVendor = null;
        String implTitle = null;
        String implVersion = null;
        String implVendor = null;
        String sealed = null;
        URL sealBase = null;

        if (man != null) {
            Attributes attr = man.getAttributes(pn.replace('.', '/').concat("/"));
            if (attr != null) {
                specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                sealed = attr.getValue(Attributes.Name.SEALED);
            }

            attr = man.getMainAttributes();
            if (attr != null) {
                if (specTitle == null)
                    specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                if (specVersion == null)
                    specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                if (specVendor == null)
                    specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                if (implTitle == null)
                    implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                if (implVersion == null)
                    implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (implVendor == null)
                    implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                if (sealed == null)
                    sealed = attr.getValue(Attributes.Name.SEALED);
            }

            // package is sealed
            if ("true".equalsIgnoreCase(sealed))
                sealBase = url;
        }
        return definePackage(pn,
                             specTitle,
                             specVersion,
                             specVendor,
                             implTitle,
                             implVersion,
                             implVendor,
                             sealBase);
    }

    /**
     * Returns {@code true} if the specified package name is sealed according to
     * the given manifest.
     */
    private boolean isSealed(String pn, Manifest man) {
        String path = pn.replace('.', '/').concat("/");
        Attributes attr = man.getAttributes(path);
        String sealed = null;
        if (attr != null)
            sealed = attr.getValue(Attributes.Name.SEALED);
        if (sealed == null && (attr = man.getMainAttributes()) != null)
            sealed = attr.getValue(Attributes.Name.SEALED);
        return "true".equalsIgnoreCase(sealed);
    }

    // -- permissions

    /**
     * Returns the permissions for the given CodeSource.
     */
    @Override
    protected PermissionCollection getPermissions(CodeSource cs) {
        PermissionCollection perms = super.getPermissions(cs);

        // add the permission to access the resource
        URL url = cs.getLocation();
        if (url == null)
            return perms;
        Permission p = null;
        try {
            p = url.openConnection().getPermission();
            if (p != null) {
                // for directories then need recursive access
                if (p instanceof FilePermission) {
                    String path = p.getName();
                    if (path.endsWith(File.separator)) {
                        path += "-";
                        p = new FilePermission(path, "read");
                    }
                }
                perms.add(p);
            }
        } catch (IOException ioe) { }

        return perms;
    }


    // -- miscellaneous supporting methods

    /**
     * Returns the ModuleReader for the given module.
     */
    private ModuleReader moduleReaderFor(ModuleReference mref) {
        return moduleToReader.computeIfAbsent(mref, m -> createModuleReader(mref));
    }

    /**
     * Creates a ModuleReader for the given module.
     */
    private ModuleReader createModuleReader(ModuleReference mref) {
        ModuleReader reader;

        try {
            reader = mref.open();
        } catch (IOException e) {
            // Return a null module reader to avoid a future class load
            // attempting to open the module again.
            return new NullModuleReader();
        }

        // if -Xpatch is specified then wrap the ModuleReader so that the
        // patch directories are searched first
        if (!patchDirs.isEmpty()) {
            String mn = mref.descriptor().name();
            reader = new OverrideModuleReader(mn, patchDirs, reader);
        }

        return reader;
    }

    /**
     * A ModuleReader that doesn't read any resources.
     */
    private static class NullModuleReader implements ModuleReader {
        @Override
        public Optional<URI> find(String name) {
            return Optional.empty();
        }
        @Override
        public void close() {
            throw new InternalError("Should not get here");
        }
    };

    /**
     * A ModuleReader to prepend a sequence of patch directories to
     * another ModuleReader.
     */
    private static class OverrideModuleReader implements ModuleReader {
        private final String module;
        private final List<Path> patchDirs;
        private final ModuleReader reader;

        OverrideModuleReader(String module,
                             List<Path> patchDirs,
                             ModuleReader reader) {
            this.module = module;
            this.patchDirs = patchDirs;
            this.reader = reader;
        }

        /**
         * Returns the path to the resource in the first patch directory
         * where the resource is found.
         */
        private Path findResource(String name) {
            for (Path patchDir : patchDirs) {
                Path dir = patchDir.resolve(module);

                Path path = Paths.get(name.replace('/', File.separatorChar));
                if (path.getRoot() == null) {
                    path = dir.resolve(path);
                } else {
                    // drop the root component so that the resource is
                    // located relative to the module directory
                    int n = path.getNameCount();
                    if (n == 0) return null;
                    path = dir.resolve(path.subpath(0, n));
                }

                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
            return null;
        }

        @Override
        public Optional<URI> find(String name) throws IOException {
            Path path = findResource(name);
            if (path != null) {
                try {
                    return Optional.of(path.toUri());
                } catch (IOError e) {
                    throw (IOException) e.getCause();
                }
            } else {
                return reader.find(name);
            }
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            Path path = findResource(name);
            if (path != null) {
                return Optional.of(Files.newInputStream(path));
            } else {
                return reader.open(name);
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            Path path = findResource(name);
            if (path != null) {
                return Optional.of(ByteBuffer.wrap(Files.readAllBytes(path)));
            } else {
                return reader.read(name);
            }
        }

        @Override
        public void release(ByteBuffer bb) {
            if (bb.isDirect())
                reader.release(bb);
        }

        @Override
        public void close() throws IOException {
            throw new InternalError("Should not get here");
        }
    }

    /**
     * Checks access to the given URL. We use URLClassPath for consistent
     * checking with java.net.URLClassLoader.
     */
    private static URL checkURL(URL url) {
        return URLClassPath.checkURL(url);
    }
}
