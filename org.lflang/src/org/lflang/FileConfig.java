package org.lflang;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.generator.IFileSystemAccess2;

import org.lflang.util.FileUtil;

/**
 * Base class that governs the interactions between code generators and the file system.
 *  
 * @author Marten Lohstroh
 *
 */
public class FileConfig {

    // Public static fields.
    
    public static final String DEFAULT_SRC_DIR = "src";
    
    /**
     * Default name of the directory to store binaries in.
     */
    public static final String DEFAULT_BIN_DIR = "bin";
    
    /**
     * Default name of the directory to store generated sources in.
     */
    public static final String DEFAULT_SRC_GEN_DIR = "src-gen";

    // Public fields.

    /**
     * The directory in which to put binaries, if the code generator produces any.
     */
    public final Path binPath;

    /**
     * The name of the main reactor, which has to match the file name (without
     * the '.lf' extension).
     */
    public final String name;

    /**
     * The directory that is the root of the package in which the .lf source file resides. This path is determined
     * differently depending on whether the compiler is invoked through the IDE or from the command line. In the former
     * case, the package is the project root that the source resides in. In the latter case, it is the parent directory
     * of the nearest `src` directory up the hierarchy, if there is one, or just the `outPath` if there is none. It is
     * recommended to always keep the sources in a `src` directory regardless of the workflow, in which case the
     * output behavior will be identical irrespective of the way the compiler is invoked.
     */
    public final Path srcPkgPath;

    /**
     * The file containing the main source code.
     * This is the Eclipse eCore view of the file, which is distinct
     * from the XText view of the file and the OS view of the file.
     */
    public final Resource resource;

    /**
     * If running in an Eclipse IDE, the iResource refers to the
     * IFile representing the Lingua Franca program.
     * This is the XText view of the file, which is distinct
     * from the Eclipse eCore view of the file and the OS view of the file.
     *
     * This is null if running outside an Eclipse IDE.
     */
    public final IResource iResource;

    /**
     * The full path to the file containing the .lf file including the
     * full filename with the .lf extension.
     */
    public final Path srcFile;

    /**
     * The directory in which the source .lf file was found.
     */
    public final Path srcPath;

    /**
     * Indicate whether the bin directory should be hierarchical.
     */
    public final boolean useHierarchicalBin;

    // Protected fields.

    /**
     * Path representation of srcGenRoot, the root directory for generated
     * sources.
     */
    protected Path srcGenBasePath;

    /**
     * The directory in which to put the generated sources.
     * This takes into account the location of the source file relative to the
     * package root. Specifically, if the source file is x/y/Z.lf relative
     * to the package root, then the generated sources will be put in x/y/Z
     * relative to srcGenBasePath.
     */
    protected Path srcGenPath;


    // private fields

    /**
     * The parent of the directory designated for placing generated sources into (`./src-gen` by default). Additional
     * directories (such as `bin` or `build`) should be created as siblings of the directory for generated sources,
     * which means that such directories should be created relative to the path assigned to this class variable.
     *
     * The generated source directory is specified in the IDE (Project Properties->LF->Compiler->Output Folder). When
     * invoking the standalone compiler, the output path is specified directly using the `-o` or `--output-path` option.
     */
    private final Path outPath;

    /**
     * The directory that denotes the root of the package to which the
     * generated sources belong. Even if the target language does not have a
     * notion of packages, this directory groups all files associated with a
     * single main reactor.
     * of packages.
     */
    private final Path srcGenPkgPath;

    public FileConfig(Resource resource, Path srcGenBasePath, boolean useHierarchicalBin) throws IOException {
        this.resource = resource;
        this.useHierarchicalBin = useHierarchicalBin;

        this.srcFile = FileUtil.toPath(this.resource);

        this.srcPath = srcFile.getParent();
        this.srcPkgPath = getPkgPath(resource);

        this.srcGenBasePath = srcGenBasePath;
        this.name = FileUtil.nameWithoutExtension(this.srcFile);
        this.srcGenPath = srcGenBasePath.resolve(getSubPkgPath(srcPath)).resolve(name);
        this.srcGenPkgPath = this.srcGenPath;
        this.outPath = srcGenBasePath.getParent();

        Path binRoot = outPath.resolve(DEFAULT_BIN_DIR);
        this.binPath = useHierarchicalBin ? binRoot.resolve(getSubPkgPath(srcPath)) : binRoot;

        this.iResource = FileUtil.getIResource(resource);
    }
    
    /**
     * Get the directory a resource is located in relative to the root package
     */
    public Path getDirectory(Resource r) throws IOException {
        return getSubPkgPath(FileUtil.toPath(r).getParent());
    }

    /**
     * The parent of the directory designated for placing generated sources into (`./src-gen` by default). Additional
     * directories (such as `bin` or `build`) should be created as siblings of the directory for generated sources,
     * which means that such directories should be created relative to the path assigned to this class variable.
     *
     * The generated source directory is specified in the IDE (Project Properties->LF->Compiler->Output Folder). When
     * invoking the standalone compiler, the output path is specified directly using the `-o` or `--output-path` option.
     */
    public Path getOutPath() {
        return outPath;
    }

    /**
     * The directory in which to put the generated sources.
     * This takes into account the location of the source file relative to the
     * package root. Specifically, if the source file is x/y/Z.lf relative
     * to the package root, then the generated sources will be put in x/y/Z
     * relative to srcGenBasePath.
     */
    public Path getSrcGenPath() {
        return srcGenPath;
    }


    /**
     * Path representation of srcGenRoot, the root directory for generated
     * sources. This is the root, meaning that if the source file is x/y/Z.lf
     * relative to the package root, then the generated sources will be put in x/y/Z
     * relative to this URI.
     */
    public Path getSrcGenBasePath() {
        return srcGenBasePath;
    }

    /**
     * The directory that denotes the root of the package to which the
     * generated sources belong. Even if the target language does not have a
     * notion of packages, this directory groups all files associated with a
     * single main reactor.
     */
    public Path getSrcGenPkgPath() {
        return srcGenPkgPath;
    }

    /**
     * Returns the root directory for generated sources.
     */
    public static Path getSrcGenRoot(IFileSystemAccess2 fsa) throws IOException {
        URI srcGenURI = fsa.getURI("");
        if (srcGenURI.hasTrailingPathSeparator()) {
            srcGenURI = srcGenURI.trimSegments(1);
        }
        return FileUtil.toPath(srcGenURI);
    }
    
    /**
     * Given a path that denotes the full path to a source file (not including the
     * file itself), return the relative path from the root of the 'src'
     * directory, or, if there is no 'src' directory, the relative path 
     * from the root of the package.
     * @param srcPath The path to the source.
     * @return the relative path from the root of the 'src'
     * directory, or, if there is no 'src' directory, the relative path
     * from the root of the package
     */
    protected Path getSubPkgPath(Path srcPath) {
        Path relSrcPath = srcPkgPath.relativize(srcPath);
        if (relSrcPath.startsWith(DEFAULT_SRC_DIR)) {
            int segments = relSrcPath.getNameCount(); 
            if (segments == 1) {
                return Paths.get("");
            } else {
                relSrcPath = relSrcPath.subpath(1, segments);
            }
        }
        return relSrcPath;
    }

    /**
     * Clean any artifacts produced by the code generator and target compilers.
     * 
     * The base implementation deletes the bin and src-gen directories. If the
     * target code generator creates additional files or directories, the
     * corresponding generator should override this method.
     * 
     * @throws IOException If an I/O error occurs.
     */
    public void doClean() throws IOException {
        FileUtil.deleteDirectory(binPath);
        FileUtil.deleteDirectory(srcGenBasePath);
    }

    private static Path getPkgPath(Resource resource) throws IOException {
        if (resource.getURI().isPlatform()) {
            // We are in the RCA.
            Path srcFile = FileUtil.toPath(resource);
            for (IProject r : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                Path p = Paths.get(r.getLocation().toFile().getAbsolutePath());
                Path f = srcFile.toAbsolutePath();
                if (f.startsWith(p)) {
                    return p;
                }
            }
        }
        return findPackageRoot(FileUtil.toPath(resource), s -> {});
    }

    /**
     * Find the package root by looking for an 'src'
     * directory. If none can be found, return the current
     * working directory instead.
     *
     * @param input The *.lf file to find the package root
     *              for.
     * @return The package root, or the current working
     * directory if none exists.
     */
    public static Path findPackageRoot(final Path input, final Consumer<String> printWarning) {
        Path p = input;
        do {
            p = p.getParent();
            if (p == null) {
                printWarning.accept("File '" + input.getFileName() + "' is not located in an 'src' directory.");
                printWarning.accept("Adopting the current working directory as the package root.");
                return Paths.get(".").toAbsolutePath(); // todo #1478 replace with Io::getWd
            }
        } while (!p.toFile().getName().equals("src"));
        return p.getParent();
    }

}
