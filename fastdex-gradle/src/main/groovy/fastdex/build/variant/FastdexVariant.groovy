package fastdex.build.variant

import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.api.ApplicationVariant
import com.github.typ0520.fastdex.Version
import fastdex.build.extension.FastdexExtension
import fastdex.build.lib.snapshoot.sourceset.PathInfo
import fastdex.build.task.FastdexInstantRunTask
import fastdex.build.transform.FastdexTransform
import fastdex.build.util.Constants
import fastdex.build.util.FastdexInstantRun
import fastdex.build.util.FastdexRuntimeException
import fastdex.common.ShareConstants
import fastdex.common.utils.SerializeUtils
import fastdex.build.util.LibDependency
import fastdex.build.util.MetaInfo
import fastdex.build.util.ProjectSnapshoot
import fastdex.build.util.FastdexUtils
import fastdex.common.utils.FileUtils
import fastdex.build.util.GradleUtils
import fastdex.build.util.TagManager
import org.gradle.api.Project

/**
 * Created by tong on 17/3/10.
 */
public class FastdexVariant {
    final Project project
    final FastdexExtension configuration
    final ApplicationVariant androidVariant
    final String variantName
    final String manifestPath
    final File rootBuildDir
    final File buildDir
    final ProjectSnapshoot projectSnapshoot
    final TagManager tagManager
    final Set<LibDependency> libraryDependencies

    String originPackageName
    String mergedPackageName
    boolean hasDexCache
    boolean firstPatchBuild
    boolean initialized
    boolean needExecDexMerge
    boolean hasJarMergingTask
    boolean compiledByCustomJavac
    MetaInfo metaInfo
    FastdexTransform fastdexTransform
    FastdexInstantRun fastdexInstantRun
    FastdexInstantRunTask fastdexInstantRunTask

    TransformInvocation transformInvocation

    FastdexVariant(Project project, Object androidVariant) {
        this.project = project
        this.androidVariant = androidVariant

        this.configuration = project.fastdex
        this.variantName = androidVariant.name.capitalize()
        this.manifestPath = androidVariant.outputs.first().processManifest.manifestOutputFile
        this.rootBuildDir = FastdexUtils.getBuildDir(project)
        this.buildDir = FastdexUtils.getBuildDir(project,variantName)

        projectSnapshoot = new ProjectSnapshoot(this)
        tagManager = new TagManager(this.project,this.variantName)
        libraryDependencies = LibDependency.resolveProjectDependency(project,androidVariant)

        if (configuration.dexMergeThreshold <= 1) {
            throw new FastdexRuntimeException("DexMergeThreshold must be greater than 1!!")
        }
    }

    /*
    * 检查缓存是否过期，如果过期就删除
    *
    * 检查meta-info文件是否存在(app/build/fastdex/${variantName}/fastdex-meta-info.json)
    * 检查当前的依赖列表和全两打包时的依赖是否一致(app/build/fastdex/${variantName}/dependencies.json)
    * 检查资源映射文件是否存在(app/build/fastdex/${variantName}/r/r.txt)
    */
    void prepareEnv() {
        if (initialized) {
            return
        }
        initialized = true
        hasDexCache = FastdexUtils.hasDexCache(project,variantName)

        project.logger.error("==fastdex hasDexCache: ${hasDexCache}")
        if (hasDexCache) {
            File diffResultSetFile = FastdexUtils.getDiffResultSetFile(project,variantName)
            if (!FileUtils.isLegalFile(diffResultSetFile)) {
                firstPatchBuild = true
            }

            try {
                File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
                if (!FileUtils.isLegalFile(metaInfoFile)) {
                    throw new CheckException("miss file : ${metaInfoFile}")
                }
                metaInfo = MetaInfo.load(project,variantName)
                if (metaInfo == null) {
                    throw new CheckException("parse json content fail: ${FastdexUtils.getMetaInfoFile(project,variantName)}")
                }

                if (metaInfo.fastdexVersion == null) {
                    throw new CheckException("cache already expired")
                }

                if (metaInfo.fastdexVersion == null || !Version.FASTDEX_BUILD_VERSION.equals(metaInfo.fastdexVersion)) {
                    File dxJarFile = new File(FastdexUtils.getBuildDir(project),"fastdex-dx.jar")
                    File dxCommandFile = new File(FastdexUtils.getBuildDir(project),"fastdex-dx")
                    File fastdexRuntimeDex = new File(buildDir, Constants.RUNTIME_DEX_FILENAME)

                    FileUtils.deleteFile(dxJarFile)
                    FileUtils.deleteFile(dxCommandFile)
                    FileUtils.deleteFile(fastdexRuntimeDex)

                    throw new CheckException("cache fastdexVersion: ${metaInfo.fastdexVersion}, current fastdexVersion: ${Version.FASTDEX_BUILD_VERSION}")
                }

                File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedDependListFile)) {
                    throw new CheckException("miss depend list file: ${cachedDependListFile}")
                }

                File sourceSetSnapshootFile = FastdexUtils.getSourceSetSnapshootFile(project,variantName)
                if (!FileUtils.isLegalFile(sourceSetSnapshootFile)) {
                    throw new CheckException("miss sourceSet snapshoot file: ${sourceSetSnapshootFile}")
                }

                File resourceMappingFile = FastdexUtils.getResourceMappingFile(project,variantName)
                if (!FileUtils.isLegalFile(resourceMappingFile)) {
                    throw new CheckException("miss resource mapping file: ${resourceMappingFile}")
                }

                File androidManifestStatFile = FastdexUtils.getAndroidManifestStatFile(project,variantName)
                if (!FileUtils.isLegalFile(androidManifestStatFile)) {
                    throw new CheckException("miss android manifest stat file: ${androidManifestStatFile}")
                }

                if (metaInfo.mergedDexVersion > 0) {
                    File mergedPatchDex = FastdexUtils.getMergedPatchDex(project,variantName)
                    if (!FileUtils.isLegalFile(androidManifestStatFile)) {
                        throw new CheckException("miss merged dex file: ${mergedPatchDex}")
                    }
                }

                if (configuration.useCustomCompile) {
                    File classpathFile = new File(FastdexUtils.getBuildDir(project,variantName),Constants.CLASSPATH_FILENAME)
                    if (!FileUtils.isLegalFile(classpathFile)) {
                        throw new CheckException("miss classpath file: ${classpathFile}")
                    }
                }

                String oldRootProjectPath = metaInfo.rootProjectPath
                String curRootProjectPath = project.rootProject.projectDir.absolutePath
                boolean isRootProjectDirChanged = metaInfo.isRootProjectDirChanged(curRootProjectPath)
                if (isRootProjectDirChanged) {
                    throw new CheckException("project path changed old: ${oldRootProjectPath} now: ${curRootProjectPath}")
                }
                projectSnapshoot.loadSnapshoot()
                if (projectSnapshoot.isDependenciesChanged()) {
                    throw new CheckException("dependencies changed")
                }
            } catch (Throwable e) {
                hasDexCache = false

                if (!(e instanceof CheckException) && configuration.debug) {
                    e.printStackTrace()
                }

                project.logger.error("==fastdex ${e.getMessage()}")
                project.logger.error("==fastdex we will remove ${variantName.toLowerCase()} cache")
            }
        }

        if (hasDexCache && metaInfo != null) {
            project.logger.error("==fastdex discover dex cache for ${variantName.toLowerCase()}")
        }
        else {
            metaInfo = new MetaInfo()
            metaInfo.fastdexVersion = Version.FASTDEX_BUILD_VERSION

            metaInfo.projectPath = project.projectDir.absolutePath
            metaInfo.rootProjectPath = project.rootProject.projectDir.absolutePath
            metaInfo.variantName = variantName
            FastdexUtils.cleanCache(project,variantName)
            FileUtils.ensumeDir(buildDir)
        }

        projectSnapshoot.prepareEnv()
        if (hasDexCache) {
            Set<PathInfo> addOrModifiedPathInfos = new HashSet<>()
            File aptDir = GradleUtils.getAptOutputDir(androidVariant)

            for (PathInfo pathInfo : projectSnapshoot.diffResultSet.addOrModifiedPathInfos) {
                //忽略掉apt目录
                if (!aptDir.equals(new File(pathInfo.path))) {
                    addOrModifiedPathInfos.add(pathInfo)
                }
            }
            needExecDexMerge = addOrModifiedPathInfos.size() >= configuration.dexMergeThreshold
        }
        fastdexInstantRun.onFastdexPrepare()
    }

    /**
     * 获取原始manifest文件的package节点的值
     * @return
     */
    public String getOriginPackageName() {
        if (originPackageName != null) {
            return originPackageName
        }
        String path = project.android.sourceSets.main.manifest.srcFile.absolutePath
        originPackageName = GradleUtils.getPackageName(path)
        return originPackageName
    }

    /**
     * 获取合并以后的manifest文件的package节点的值
     * @return
     */
    public String getMergedPackageName() {
        if (mergedPackageName != null) {
            return mergedPackageName
        }
        mergedPackageName = GradleUtils.getPackageName(manifestPath)
        return mergedPackageName
    }

    /**
     * 当dex生成以后
     * @param nornalBuild
     */
    public void onDexGenerateSuccess(boolean nornalBuild,boolean dexMerge) {
        if (nornalBuild) {
            saveMetaInfo()
            copyRTxt()
        }
        else {
            if (dexMerge) {
                //移除idx.xml public.xml
                File idsXmlFile = FastdexUtils.getIdxXmlFile(project,variantName)
                File publicXmlFile = FastdexUtils.getPublicXmlFile(project,variantName)
                FileUtils.deleteFile(idsXmlFile)
                FileUtils.deleteFile(publicXmlFile)

                copyRTxt()
            }
        }
        projectSnapshoot.onDexGenerateSuccess(nornalBuild,dexMerge)
        fastdexInstantRun.onSourceChanged()
    }

    def saveMetaInfo() {
        File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
        SerializeUtils.serializeTo(new FileOutputStream(metaInfoFile),metaInfo)
    }

    def copyMetaInfo2Assets() {
        File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
        File assetsPath = androidVariant.getVariantData().getScope().getMergeAssetsOutputDir()

        File dest = new File(assetsPath,metaInfoFile.getName())

        project.logger.error("==fastdex copy meta info: \nfrom: " + metaInfoFile + "\ninto: " + dest)
        FileUtils.copyFileUsingStream(metaInfoFile,dest)
    }

    def onPrePackage() {
        copyMetaInfo2Assets()

//        if (hasDexCache) {
//            if (metaInfo.packageUsingPatchDexVersion >= metaInfo.patchDexVersion) {
//                project.logger.error("==fastdex skip copy dex")
//                return
//            }
//            fastdexTransform.hookPatchBuildDex(fastdexTransform.hookPatchBuildDexArgs)
//        }
    }

    /**
     * 保存资源映射文件
     */
    def copyRTxt() {
        File rtxtFile = new File(androidVariant.getVariantData().getScope().getSymbolLocation(),"R.txt")
        if (!FileUtils.isLegalFile(rtxtFile)) {
            rtxtFile = new File(project.buildDir,"${File.separator}intermediates${File.separator}symbols${File.separator}${androidVariant.dirName}${File.separator}R.txt")
        }
        FileUtils.copyFileUsingStream(rtxtFile,FastdexUtils.getResourceMappingFile(project,variantName))
    }

    /**
     * 补丁打包是否需要执行dex merge
     * @return
     */
    def willExecDexMerge() {
        return needExecDexMerge
        //return hasDexCache && projectSnapshoot.diffResultSet.changedJavaFileDiffInfos.size() >= configuration.dexMergeThreshold
    }

    def getVariantConfiguration() {
        return androidVariant.getVariantData().getVariantConfiguration()
    }

    private class CheckException extends Exception {
        CheckException(String var1) {
            super(var1)
        }

        CheckException(Throwable var1) {
            super(var1)
        }
    }
}
