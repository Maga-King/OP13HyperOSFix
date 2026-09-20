/*
 * Core package-manager behavior is adapted from HyperCeiler's CorePatch rules.
 * HyperCeiler is licensed under GNU AGPL-3.0-or-later.
 */
package local.mio.op13hyperosfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class CorePatchHooks {
    private static final int INSTALL_REQUEST_DOWNGRADE_FLAG = 0x01000000;
    private static final int INSTALL_FAILED_BAD_SIGNATURE = -103;
    private static final int CAPABILITY_PERMISSION = 4;
    private static final int CAPABILITY_AUTH = 16;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> PERSISTENT_INSTALL =
            ThreadLocal.withInitial(() -> false);

    private static final String FALLBACK_SIGNATURE =
            "308203c6308202aea003020102021426d148b7c65944abcf3a683b4c3dd3b139c4ec85300d06092a864886f70d01010b05003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3139303130323138353233385a170d3439303130323138353233385a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820122300d06092a864886f70d01010105000382010f003082010a028201010087fcde48d9beaeba37b733a397ae586fb42b6c3f4ce758dc3ef1327754a049b58f738664ece587994f1c6362f98c9be5fe82c72177260c390781f74a10a8a6f05a6b5ca0c7c5826e15526d8d7f0e74f2170064896b0cf32634a388e1a975ed6bab10744d9b371cba85069834bf098f1de0205cdee8e715759d302a64d248067a15b9beea11b61305e367ac71b1a898bf2eec7342109c9c5813a579d8a1b3e6a3fe290ea82e27fdba748a663f73cca5807cff1e4ad6f3ccca7c02945926a47279d1159599d4ecf01c9d0b62e385c6320a7a1e4ddc9833f237e814b34024b9ad108a5b00786ea15593a50ca7987cbbdc203c096eed5ff4bf8a63d27d33ecc963990203010001a350304e300c0603551d13040530030101ff301d0603551d0e04160414a361efb002034d596c3a60ad7b0332012a16aee3301f0603551d23041830168014a361efb002034d596c3a60ad7b0332012a16aee3300d06092a864886f70d01010b0500038201010022ccb684a7a8706f3ee7c81d6750fd662bf39f84805862040b625ddf378eeefae5a4f1f283deea61a3c7f8e7963fd745415153a531912b82b596e7409287ba26fb80cedba18f22ae3d987466e1fdd88e440402b2ea2819db5392cadee501350e81b8791675ea1a2ed7ef7696dff273f13fb742bb9625fa12ce9c2cb0b7b3d94b21792f1252b1d9e4f7012cb341b62ff556e6864b40927e942065d8f0f51273fcda979b8832dd5562c79acf719de6be5aee2a85f89265b071bf38339e2d31041bc501d5e0c034ab1cd9c64353b10ee70b49274093d13f733eb9d3543140814c72f8e003f301c7a00b1872cc008ad55e26df2e8f07441002c4bcb7dc746745f0db";

    private CorePatchHooks() {
    }

    static void installIfEnabled(ClassLoader loader) {
        if (!ModuleConfig.hasAnyCoreHookEnabled()) {
            HookLog.info("package-manager patches disabled");
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        if (ModuleConfig.coreMasterEnabled()) {
            installDowngrade(loader);
            installAuthAndIntegrity(loader);
            installDigestAndPreSignature(loader);
            installExactSignature(loader);
            installSharedUser(loader);
            installVerificationAgent(loader);
        }
        installIndependentRules(loader);
        applySharedUserStaticFlag(loader);
        HookLog.info("package-manager patches installed for API 37-compatible signatures");
    }

    private static void installDowngrade(ClassLoader loader) {
        Class<?> utils = findClass(loader, "com.android.server.pm.PackageManagerServiceUtils");
        int count = hookMethods(utils, "checkDowngrade", method ->
                        method.getParameterTypes().length == 2,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DOWNGRADE)) {
                            param.setResult(null);
                        }
                    }
                });
        HookLog.info("CorePatch downgrade hooks=" + count);
    }

    private static void installAuthAndIntegrity(ClassLoader loader) {
        hookAll(loader, "com.android.server.pm.ScanPackageUtils",
                "assertMinSignatureSchemeIsValid", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_AUTH)) {
                            param.setResult(null);
                        }
                    }
                });

        Class<?> strictVerifier = findClass(loader, "android.util.jar.StrictJarVerifier");
        if (strictVerifier != null) {
            XposedBridge.hookAllConstructors(strictVerifier, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_AUTH)) {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject,
                                    "signatureSchemeRollbackProtectionsEnforced", false);
                        } catch (Throwable error) {
                            HookLog.once("core_rollback_field",
                                    "cannot disable signature rollback protection: " + error);
                        }
                    }
                }
            });
        }

        hookAll(loader, "android.util.jar.StrictJarVerifier", "verifyMessageDigest",
                returnWhen(ModuleConfig.CORE_AUTH, true, true));
        hookAll(loader, "android.util.jar.StrictJarVerifier", "verify",
                returnWhen(ModuleConfig.CORE_AUTH, true, true));
        hookAll(loader, "java.security.MessageDigest", "isEqual",
                returnWhen(ModuleConfig.CORE_AUTH, true, true));
        hookAll(loader, "android.content.res.AssetManager", "containsAllocatedTable",
                returnWhen(ModuleConfig.CORE_AUTH, false, true));

        hookAll(loader, "android.util.apk.ApkSignatureVerifier",
                "getMinimumSignatureSchemeVersionForTargetSdk", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ModuleConfig.coreMasterEnabled()) {
                            return;
                        }
                        if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_AUTH)) {
                            param.setResult(0);
                        } else if (ModuleConfig.coreFeatureEnabled(
                                ModuleConfig.CORE_DISABLE_INTEGRITY)) {
                            param.setResult(1);
                        }
                    }
                });

        Class<?> signingBlock = findClass(loader, "android.util.apk.ApkSigningBlockUtils");
        hookMethods(signingBlock, "parseVerityDigestAndVerifySourceLength",
                method -> method.getParameterTypes().length == 3,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_AUTH)
                                && param.args[0] instanceof byte[]) {
                            param.setResult(Arrays.copyOfRange((byte[]) param.args[0], 0, 32));
                        }
                    }
                });
        hookMethods(signingBlock, "verifyIntegrityForVerityBasedAlgorithm",
                method -> method.getParameterTypes().length == 3,
                returnWhen(ModuleConfig.CORE_AUTH, null, true));

        Class<?> utils = findClass(loader, "com.android.server.pm.PackageManagerServiceUtils");
        deoptimizeNamedMethods(utils, "canJoinSharedUserId");
        deoptimizeNamedMethods(utils, "verifySignatures");
        hookMethods(utils, "verifySignatures",
                method -> method.getReturnType() == Boolean.TYPE,
                returnWhen(ModuleConfig.CORE_AUTH, false, true));

        installVerifyV1Fallback(loader);
    }

    private static void installDigestAndPreSignature(ClassLoader loader) {
        Class<?> installHelper = findClass(loader, "com.android.server.pm.InstallPackageHelper");
        hookMethods(installHelper, "doesSignatureMatchForPermissions",
                method -> method.getParameterTypes().length == 3,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ModuleConfig.usePreSignatureEnabled()
                                || !Boolean.FALSE.equals(param.getResult())) {
                            return;
                        }
                        try {
                            String parsedName = String.valueOf(
                                    XposedHelpers.callMethod(param.args[1], "getPackageName"));
                            if (Objects.equals(parsedName, param.args[0])) {
                                param.setResult(true);
                            }
                        } catch (Throwable error) {
                            HookLog.error("CorePatch permission signature fallback failed", error);
                        }
                    }
                });

        Class<?> signingDetails = findClass(loader, "android.content.pm.SigningDetails");
        hookMethods(signingDetails, "checkCapability",
                method -> method.getParameterTypes().length == 2
                        && method.getParameterTypes()[1] == Integer.TYPE,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)) {
                            return;
                        }
                        int capability = (Integer) param.args[1];
                        if (capability != CAPABILITY_PERMISSION && capability != CAPABILITY_AUTH) {
                            param.setResult(true);
                        }
                    }
                });

        installStrictJarBytesFallback(loader);
        hookAll(loader, "android.content.pm.ApplicationInfo",
                "isPackageWhitelistedForHiddenApis", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)
                                || !(param.thisObject instanceof ApplicationInfo)) {
                            return;
                        }
                        ApplicationInfo info = (ApplicationInfo) param.thisObject;
                        if ((info.flags & (ApplicationInfo.FLAG_SYSTEM
                                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                            param.setResult(true);
                        }
                    }
                });
        installUpgradeKeySetFallback(loader);
    }

    private static void installExactSignature(ClassLoader loader) {
        hookAll(loader, "android.content.pm.SigningDetails", "signaturesMatchExactly",
                returnWhen(ModuleConfig.CORE_EXACT_SIGNATURE, true, true));
    }

    private static void installVerificationAgent(ClassLoader loader) {
        hookAll(loader, "com.android.server.pm.VerifyingSession", "isVerificationEnabled",
                returnWhen(ModuleConfig.CORE_DISABLE_VERIFICATION, false, true));
    }

    private static void installSharedUser(ClassLoader loader) {
        Class<?> signingDetails = findClass(loader, "android.content.pm.SigningDetails");
        hookMethods(signingDetails, "hasCommonAncestor",
                method -> method.getParameterTypes().length == 1,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ModuleConfig.sharedUserEnabled()
                                && stackContains("verifySignatures")) {
                            param.setResult(true);
                        }
                    }
                });

        Class<?> sharedUser = findClass(loader, "com.android.server.pm.SharedUserSetting");
        hookAllMethodsOnSharedUser(sharedUser, "removePackage", false);
        hookAllMethodsOnSharedUser(sharedUser, "addPackage", true);
        deoptimizeNamedMethods(findClass(loader,
                "com.android.server.pm.ReconcilePackageUtils"), "reconcilePackages");
    }

    private static void hookAllMethodsOnSharedUser(Class<?> target, String name, boolean adding) {
        hookMethods(target, name, method -> method.getParameterTypes().length == 1,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ModuleConfig.sharedUserEnabled() || param.args[0] == null) {
                            return;
                        }
                        try {
                            updateSharedUserSignature(param.thisObject, param.args[0], adding);
                        } catch (Throwable error) {
                            HookLog.error("CorePatch shared-user " + name + " failed", error);
                        }
                    }
                });
    }

    private static void updateSharedUserSignature(Object sharedUser, Object changed,
            boolean adding) throws Throwable {
        int flags = XposedHelpers.getIntField(sharedUser, "uidFlags");
        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return;
        }
        Object sharedSignature = getSettingSigningDetails(sharedUser);
        Object packages = XposedHelpers.getObjectField(sharedUser, "mPackages");
        int size = (Integer) XposedHelpers.callMethod(packages, "size");
        boolean found = false;
        Object newSignature = null;
        for (int index = 0; index < size; index++) {
            Object packageSetting = XposedHelpers.callMethod(packages, "valueAt", index);
            if (changed.equals(packageSetting)) {
                found = true;
                if (!adding) {
                    continue;
                }
                packageSetting = changed;
            }
            Object packageSignature = getSettingSigningDetails(packageSetting);
            if ((Boolean) callOriginal(packageSignature, "checkCapability",
                    sharedSignature, 0)
                    || (Boolean) callOriginal(sharedSignature, "checkCapability",
                    packageSignature, 0)) {
                return;
            }
            newSignature = newSignature == null ? packageSignature
                    : XposedHelpers.callMethod(newSignature, "mergeLineageWith",
                    packageSignature, 2);
        }
        if (!found || newSignature == null) {
            return;
        }
        Object signatures = XposedHelpers.getObjectField(sharedUser, "signatures");
        XposedHelpers.setObjectField(signatures, "mSigningDetails", newSignature);
        HookLog.info("CorePatch updated shared-user signature: " + sharedUser);
    }

    private static Object getSettingSigningDetails(Object setting) {
        Object signatures = XposedHelpers.getObjectField(setting, "signatures");
        return XposedHelpers.getObjectField(signatures, "mSigningDetails");
    }

    private static Object callOriginal(Object target, String methodName, Object... args)
            throws Throwable {
        Method method = XposedHelpers.findMethodBestMatch(target.getClass(), methodName, args);
        return XposedBridge.invokeOriginalMethod(method, target, args);
    }

    private static void installIndependentRules(ClassLoader loader) {
        if (ModuleConfig.independentFeatureEnabled(ModuleConfig.CORE_BYPASS_ISOLATION)) {
            hookAll(loader, "com.android.server.pm.PackageManagerServiceImpl",
                    "verifyIsolationViolation", returnAlways(null));
        }
        if (ModuleConfig.independentFeatureEnabled(ModuleConfig.CORE_ALLOW_SYSTEM_UPDATE)) {
            hookAll(loader, "com.android.server.pm.PackageManagerServiceImpl",
                    "canBeUpdate", returnAlways(null));
        }
        if (ModuleConfig.independentFeatureEnabled(ModuleConfig.CORE_LOW_API)) {
            hookPreparePackage(loader, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object installArgs = XposedHelpers.getObjectField(param.args[0],
                                "mInstallArgs");
                        if (installArgs != null) {
                            int flags = XposedHelpers.getIntField(installArgs, "mInstallFlags");
                            XposedHelpers.setIntField(installArgs, "mInstallFlags",
                                    flags | INSTALL_REQUEST_DOWNGRADE_FLAG);
                        }
                    } catch (Throwable error) {
                        HookLog.error("CorePatch low API flag failed", error);
                    }
                }
            });
        }
        if (ModuleConfig.independentFeatureEnabled(ModuleConfig.CORE_DISABLE_PERSISTENT)) {
            hookAll(loader, "com.android.server.pm.PackageSetting", "isPersistent",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(PERSISTENT_INSTALL.get())
                                    && Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(false);
                            }
                        }
                    });
            hookPreparePackage(loader, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    PERSISTENT_INSTALL.set(true);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    PERSISTENT_INSTALL.remove();
                }
            });
        }
        if (ModuleConfig.independentFeatureEnabled(ModuleConfig.CORE_PROTECT_FINGERPRINT)) {
            hookAll(loader,
                    "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider",
                    "copyFingerprintXml", returnAlways(null));
        }
    }

    private static void hookPreparePackage(ClassLoader loader, XC_MethodHook callback) {
        Class<?> helper = findClass(loader, "com.android.server.pm.InstallPackageHelper");
        int count = hookMethods(helper, "preparePackage",
                method -> method.getParameterTypes().length == 1, callback);
        count += hookMethods(helper, "preparePackageLI",
                method -> method.getParameterTypes().length == 1, callback);
        HookLog.info("CorePatch prepare-package hooks=" + count);
    }

    private static void applySharedUserStaticFlag(ClassLoader loader) {
        if (!ModuleConfig.sharedUserEnabled()) {
            return;
        }
        try {
            Class<?> reconcile = findClass(loader,
                    "com.android.server.pm.ReconcilePackageUtils");
            Field flag = XposedHelpers.findField(reconcile,
                    "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS");
            try {
                Field accessFlags = Field.class.getDeclaredField("accessFlags");
                accessFlags.setAccessible(true);
                accessFlags.setInt(flag, accessFlags.getInt(flag) & ~Modifier.FINAL);
            } catch (Throwable ignored) {
            }
            flag.setAccessible(true);
            flag.setBoolean(null, true);
            HookLog.info("CorePatch enabled non-preload system shared UID support");
        } catch (Throwable error) {
            HookLog.error("CorePatch shared UID static flag failed", error);
        }
    }

    private static void installStrictJarBytesFallback(ClassLoader loader) {
        try {
            Class<?> pkcs7 = findClass(loader, "sun.security.pkcs.PKCS7");
            Constructor<?> constructor = pkcs7.getDeclaredConstructor(byte[].class);
            constructor.setAccessible(true);
            hookAll(loader, "android.util.jar.StrictJarVerifier", "verifyBytes",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)
                                    || ModuleConfig.usePreSignatureEnabled()) {
                                return;
                            }
                            try {
                                Object block = constructor.newInstance(param.args[0]);
                                Object[] infos = (Object[]) XposedHelpers.callMethod(block,
                                        "getSignerInfos");
                                if (infos == null || infos.length == 0) {
                                    return;
                                }
                                @SuppressWarnings("unchecked")
                                List<X509Certificate> chain = (List<X509Certificate>)
                                        XposedHelpers.callMethod(infos[0],
                                                "getCertificateChain", block);
                                param.setResult(chain.toArray(new X509Certificate[0]));
                            } catch (Throwable error) {
                                HookLog.error("CorePatch V1 certificate fallback failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.once("core_pkcs7_missing",
                    "CorePatch PKCS7 fallback unavailable: " + error);
        }
    }

    private static void installUpgradeKeySetFallback(ClassLoader loader) {
        Class<?> keySet = findClass(loader, "com.android.server.pm.KeySetManagerService");
        if (keySet == null) {
            return;
        }
        ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);
        hookAllMethods(keySet, "shouldCheckUpgradeKeySetLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean enabled = ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)
                        && stackStartsWith("preparePackage");
                bypass.set(enabled);
                if (enabled) {
                    param.setResult(true);
                }
            }
        });
        hookAllMethods(keySet, "checkUpgradeKeySetLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)
                        && Boolean.TRUE.equals(bypass.get())) {
                    param.setResult(true);
                }
                bypass.remove();
            }
        });
    }

    private static void installVerifyV1Fallback(ClassLoader loader) {
        try {
            Class<?> strictJar = findClass(loader, "android.util.jar.StrictJarFile");
            Constructor<?> strictJarConstructor = strictJar.getDeclaredConstructor(
                    String.class, boolean.class, boolean.class);
            strictJarConstructor.setAccessible(true);
            Class<?> verifier = findClass(loader, "android.util.apk.ApkSignatureVerifier");
            Class<?> signingDetails = findClass(loader, "android.content.pm.SigningDetails");
            Constructor<?> signingConstructor = signingDetails.getDeclaredConstructor(
                    Signature[].class, Integer.TYPE);
            signingConstructor.setAccessible(true);
            Class<?> parserException = findClass(loader,
                    "android.content.pm.PackageParser$PackageParserException");
            Field errorField = XposedHelpers.findField(parserException, "error");
            Class<?> parseResult = findClass(loader,
                    "android.content.pm.parsing.result.ParseResult");
            Class<?> withDigests = findClass(loader,
                    "android.util.apk.ApkSignatureVerifier$SigningDetailsWithDigests");
            Constructor<?> withDigestsConstructor = withDigests == null ? null
                    : withDigests.getDeclaredConstructor(signingDetails, Map.class);
            if (withDigestsConstructor != null) {
                withDigestsConstructor.setAccessible(true);
            }

            hookAllMethods(verifier, "verifyV1Signature", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_AUTH)) {
                        return;
                    }
                    try {
                        repairV1Failure(param, verifier, strictJarConstructor,
                                signingConstructor, parserException, errorField,
                                parseResult, withDigestsConstructor);
                    } catch (Throwable error) {
                        HookLog.error("CorePatch verifyV1 fallback failed", error);
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.once("core_verify_v1_unavailable",
                    "CorePatch verifyV1 fallback unavailable: " + error);
        }
    }

    private static void repairV1Failure(XC_MethodHook.MethodHookParam param,
            Class<?> verifier, Constructor<?> strictJarConstructor,
            Constructor<?> signingConstructor, Class<?> parserException,
            Field errorField, Class<?> parseResult, Constructor<?> withDigestsConstructor)
            throws Throwable {
        Throwable throwable = param.getThrowable();
        Integer parseError = null;
        Object result = throwable == null ? param.getResult() : null;
        if (result != null && parseResult != null && parseResult.isInstance(result)
                && Boolean.TRUE.equals(XposedHelpers.callMethod(result, "isError"))) {
            parseError = (Integer) XposedHelpers.callMethod(result, "getErrorCode");
        }
        if (throwable == null && parseError == null) {
            return;
        }

        String apkPath = findApkPath(param.args);
        Signature[] signatures = installedSignatures(apkPath);
        if (signatures == null && ModuleConfig.coreFeatureEnabled(ModuleConfig.CORE_DIGEST)) {
            signatures = manifestSignatures(param.args, apkPath, verifier,
                    strictJarConstructor);
        }
        if (signatures == null) {
            signatures = new Signature[]{new Signature(FALLBACK_SIGNATURE)};
        }
        Object signing = signingConstructor.newInstance(signatures, 1);
        if (withDigestsConstructor != null) {
            signing = withDigestsConstructor.newInstance(signing, null);
        }

        boolean badSignature = parseError != null
                && parseError == INSTALL_FAILED_BAD_SIGNATURE;
        if (!badSignature && throwable != null) {
            badSignature = exceptionCode(throwable, parserException, errorField)
                    == INSTALL_FAILED_BAD_SIGNATURE;
            if (!badSignature && throwable.getCause() != null) {
                badSignature = exceptionCode(throwable.getCause(), parserException,
                        errorField) == INSTALL_FAILED_BAD_SIGNATURE;
            }
        }
        if (!badSignature) {
            return;
        }
        if (parseError != null && param.args.length > 0) {
            Object input = param.args[0];
            XposedHelpers.callMethod(input, "reset");
            param.setResult(XposedHelpers.callMethod(input, "success", signing));
        } else {
            param.setResult(signing);
        }
    }

    private static Signature[] installedSignatures(String apkPath) {
        if (!ModuleConfig.usePreSignatureEnabled() || apkPath == null) {
            return null;
        }
        try {
            Context context = ModuleConfig.systemContext();
            if (context == null) {
                return null;
            }
            PackageManager manager = context.getPackageManager();
            PackageInfo archive = manager.getPackageArchiveInfo(apkPath, 0);
            if (archive == null || archive.packageName == null) {
                return null;
            }
            PackageInfo installed = manager.getPackageInfo(archive.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            return installed.signingInfo == null ? null
                    : installed.signingInfo.getSigningCertificateHistory();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Signature[] manifestSignatures(Object[] args, String apkPath,
            Class<?> verifier, Constructor<?> strictJarConstructor) {
        if (apkPath == null) {
            return null;
        }
        Object jar = null;
        try {
            jar = strictJarConstructor.newInstance(apkPath, true, false);
            ZipEntry manifest = (ZipEntry) XposedHelpers.callMethod(jar,
                    "findEntry", "AndroidManifest.xml");
            Object certificatesResult;
            if (args.length > 0 && !(args[0] instanceof String)) {
                Object parsed = XposedHelpers.callStaticMethod(verifier,
                        "loadCertificates", args[0], jar, manifest);
                certificatesResult = XposedHelpers.callMethod(parsed, "getResult");
            } else {
                certificatesResult = XposedHelpers.callStaticMethod(verifier,
                        "loadCertificates", jar, manifest);
            }
            Certificate[][] certificates = (Certificate[][]) certificatesResult;
            return (Signature[]) XposedHelpers.callStaticMethod(verifier,
                    "convertToSignatures", (Object) certificates);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (jar != null) {
                try {
                    XposedHelpers.callMethod(jar, "close");
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int exceptionCode(Throwable throwable, Class<?> expected, Field field) {
        try {
            return throwable.getClass() == expected ? field.getInt(throwable) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String findApkPath(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).endsWith(".apk")) {
                return (String) arg;
            }
        }
        return null;
    }

    private static XC_MethodHook returnWhen(String key, Object result, boolean masterGated) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean enabled = masterGated
                        ? ModuleConfig.coreFeatureEnabled(key)
                        : ModuleConfig.independentFeatureEnabled(key);
                if (enabled) {
                    param.setResult(result);
                }
            }
        };
    }

    private static XC_MethodHook returnAlways(Object result) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(result);
            }
        };
    }

    private static int hookAll(ClassLoader loader, String className, String methodName,
            XC_MethodHook callback) {
        return hookAllMethods(findClass(loader, className), methodName, callback);
    }

    private static int hookAllMethods(Class<?> target, String methodName,
            XC_MethodHook callback) {
        return hookMethods(target, methodName, method -> true, callback);
    }

    private static int hookMethods(Class<?> target, String methodName,
            MethodFilter filter, XC_MethodHook callback) {
        if (target == null) {
            return 0;
        }
        int count = 0;
        for (Class<?> current = target; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!methodName.equals(method.getName()) || method.isBridge()
                        || !filter.accept(method)) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    XposedBridge.hookMethod(method, callback);
                    count++;
                } catch (Throwable error) {
                    HookLog.error("CorePatch hook failed " + method, error);
                }
            }
        }
        return count;
    }

    private static Class<?> findClass(ClassLoader loader, String name) {
        return XposedHelpers.findClassIfExists(name, loader);
    }

    private static boolean stackContains(String name) {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (name.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean stackStartsWith(String prefix) {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getMethodName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void deoptimizeNamedMethods(Class<?> target, String name) {
        if (target == null) {
            return;
        }
        for (Method method : target.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                deoptimize(method);
            }
        }
    }

    private static void deoptimize(Member member) {
        try {
            for (Method method : XposedBridge.class.getDeclaredMethods()) {
                if ("deoptimizeMethod".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    method.setAccessible(true);
                    method.invoke(null, member);
                    return;
                }
            }
            HookLog.once("core_deopt_missing", "LSPosed deoptimizeMethod is unavailable");
        } catch (Throwable error) {
            HookLog.once("core_deopt_failed", "LSPosed deoptimization failed: " + error);
        }
    }

    private interface MethodFilter {
        boolean accept(Method method);
    }
}
