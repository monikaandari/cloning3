/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.deviceinfra.ext.devicemanagement.device.platform.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import com.google.devtools.deviceinfra.ext.devicemanagement.device.BaseDeviceHelper;
import com.google.devtools.mobileharness.api.model.error.AndroidErrorId;
import com.google.devtools.mobileharness.api.model.error.MobileHarnessException;
import com.google.devtools.mobileharness.api.testrunner.device.cache.DeviceCache;
import com.google.devtools.mobileharness.platform.android.app.ActivityManager;
import com.google.devtools.mobileharness.platform.android.packagemanager.AndroidPackageManagerUtil;
import com.google.devtools.mobileharness.platform.android.process.AndroidProcessUtil;
import com.google.devtools.mobileharness.platform.android.sdktool.adb.AndroidAdbUtil;
import com.google.devtools.mobileharness.platform.android.sdktool.adb.AndroidProperty;
import com.google.devtools.mobileharness.platform.android.shared.autovalue.UtilArgs;
import com.google.devtools.mobileharness.platform.android.shared.constant.PackageConstants;
import com.google.devtools.mobileharness.platform.android.systemsetting.AndroidSystemSettingUtil;
import com.google.devtools.mobileharness.platform.android.systemstate.AndroidSystemStateUtil;
import com.google.devtools.mobileharness.shared.util.error.MoreThrowables;
import com.google.wireless.qa.mobileharness.shared.android.AndroidPackages;
import com.google.wireless.qa.mobileharness.shared.android.Sqlite;
import com.google.wireless.qa.mobileharness.shared.api.annotation.ParamAnnotation;
import com.google.wireless.qa.mobileharness.shared.api.device.BaseDevice;
import com.google.wireless.qa.mobileharness.shared.constant.Dimension;
import com.google.wireless.qa.mobileharness.shared.model.job.TestInfo;
import com.google.wireless.qa.mobileharness.shared.util.ScreenResolution;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Utility class to control an Android device (including Android real device, emulator). */
public abstract class AndroidDeviceDelegate {

  @ParamAnnotation(
      required = false,
      help =
          "Whether to enable/disable dex pre-verification before running "
              + "each test. By default, it is false so dex_pre_verification will be disabled.")
  @VisibleForTesting
  static final String PARAM_DEX_PRE_VERIFICATION = "dex_pre_verification";

  /** Packages generated by MH that should be cleared before each test */
  @VisibleForTesting
  static final String[] ANDROID_UNEXPECTED_PACKAGE =
      new String[] {"com.google.android.apps.internal.statusbarhider"};

  private static final String DIMENSION_NAME_ROOTED = "rooted";

  @VisibleForTesting static final String PROPERTY_NAME_CACHED_ABI = "cached_abi";

  @VisibleForTesting
  static final String PROPERTY_NAME_CACHED_SCREEN_DENSITY = "cached_screen_density";

  @VisibleForTesting static final String PROPERTY_NAME_CACHED_SDK_VERSION = "cached_sdk_version";

  private static final String PROPERTY_NAME_LOCALE_PATTERN = "\\w{2}-\\w{2}";

  private static final ImmutableSet<AndroidProperty> RETAIN_CAPS_PROPERTIES =
      ImmutableSet.of(AndroidProperty.SERIAL, AndroidProperty.BUILD);

  @VisibleForTesting static final Duration WAIT_FOR_DEVICE_TIMEOUT = Duration.ofMinutes(15);

  // Cache the device when waitForDevice and waitUntilReady.
  private static final Duration CACHE_TIMEOUT = WAIT_FOR_DEVICE_TIMEOUT.plusMinutes(5);

  @VisibleForTesting
  public static final ImmutableSet<AndroidProperty> PROP_NOT_SET_AS_DIMENSION =
      ImmutableSet.of(
          AndroidProperty.FLAVOR, AndroidProperty.KAIOS_RUNTIME_TOKEN, AndroidProperty.PRODUCT);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String deviceId;

  private final BaseDevice device;
  private final ActivityManager am;
  private final Sqlite sqlite;
  private final AndroidAdbUtil androidAdbUtil;
  private final AndroidSystemStateUtil androidSystemStateUtil;
  private final AndroidPackageManagerUtil androidPackageManagerUtil;
  private final AndroidSystemSettingUtil androidSystemSettingUtil;
  private final AndroidProcessUtil androidProcessUtil;

  protected AndroidDeviceDelegate(
      BaseDevice device,
      ActivityManager am,
      Sqlite sqlite,
      AndroidAdbUtil androidAdbUtil,
      AndroidSystemStateUtil androidSystemStateUtil,
      AndroidPackageManagerUtil androidPackageManagerUtil,
      AndroidSystemSettingUtil androidSystemSettingUtil,
      AndroidProcessUtil androidProcessUtil) {
    this.device = device;
    this.am = am;
    this.sqlite = sqlite;
    this.androidAdbUtil = androidAdbUtil;
    this.androidSystemStateUtil = androidSystemStateUtil;
    this.androidPackageManagerUtil = androidPackageManagerUtil;
    this.androidSystemSettingUtil = androidSystemSettingUtil;
    this.androidProcessUtil = androidProcessUtil;
    this.deviceId = device.getDeviceId();
  }

  /** Ensures device is booted up and ready to respond. */
  public void ensureDeviceReady() throws MobileHarnessException, InterruptedException {
    try {
      DeviceCache.getInstance().cache(deviceId, device.getClass().getSimpleName(), CACHE_TIMEOUT);
      androidSystemStateUtil.waitForDevice(deviceId, WAIT_FOR_DEVICE_TIMEOUT);
      androidSystemStateUtil.waitUntilReady(deviceId);
    } finally {
      DeviceCache.getInstance().invalidateCache(deviceId);
    }
  }

  /**
   * Set up the Android device.
   *
   * @param isRooted whether the device is rooted
   * @param extraDimensions extra dimensions added to the device
   */
  public void setUp(boolean isRooted, @Nullable Multimap<Dimension.Name, String> extraDimensions)
      throws MobileHarnessException, InterruptedException {
    BaseDeviceHelper.setUp(device, BaseDevice.class, extraDimensions);

    // Adds all the system properties to its dimensions.
    updateAndroidPropertyDimensions(deviceId);

    // Adds language and locale dimension from ActivityManager if any of them is missing.
    checkLocaleLanguageDimensions();

    // Adds drivers/decorators only after the properties are read. Because the validators of the
    // drivers/decorators may depend on those properties.
    basicAndroidDeviceConfiguration(isRooted);
    additionalAndroidDeviceConfiguration();
    if (isRooted) {
      rootedAndroidDeviceConfiguration(deviceId);
    }
  }

  /** Fetches language and locale if missing. */
  @VisibleForTesting
  void checkLocaleLanguageDimensions() throws MobileHarnessException, InterruptedException {
    boolean hasLoc = hasDimension(AndroidProperty.LOCALE);
    boolean hasLan = hasDimension(AndroidProperty.LANGUAGE);
    if (hasLoc && hasLan) {
      return;
    } else if (hasLoc && updateLanguageDimensionFromLocale()) {
      return;
    } else if (updateLocaleDimensionFromLanguageRegion()) {
      return;
    } else {
      updateLocaleLanguageDimensionsFromAm();
    }
  }

  private boolean hasDimension(AndroidProperty key) {
    ImmutableSet<String> values =
        ImmutableSet.copyOf(device.getDimension(Ascii.toLowerCase(key.name())));
    return values != null && !values.isEmpty();
  }

  private boolean updateDimension(AndroidProperty key, String value) {
    ImmutableSet<String> values = maybeLowerCaseProperty(key, value);
    return device.updateDimension(Ascii.toLowerCase(key.name()), values.toArray(new String[0]));
  }

  /** Updates language from locale. Return true if success. */
  private boolean updateLanguageDimensionFromLocale() {
    ImmutableSet<String> locs =
        ImmutableSet.copyOf(device.getDimension(Ascii.toLowerCase(AndroidProperty.LOCALE.name())));
    String loc = Iterables.get(locs, 0);
    if (loc != null && loc.matches(PROPERTY_NAME_LOCALE_PATTERN)) {
      ActivityManager.Locale locale = ActivityManager.Locale.create(loc);
      updateDimension(AndroidProperty.LANGUAGE, locale.language());
      return true;
    }
    return false;
  }

  /** Fetches locale from activity manager. Return true if success. */
  private boolean updateLocaleLanguageDimensionsFromAm() throws InterruptedException {
    try {
      ActivityManager.Locale locale = am.getLocale(deviceId);
      updateDimension(AndroidProperty.LANGUAGE, locale.language());
      updateDimension(AndroidProperty.LOCALE, locale.locale());
      logger.atInfo().log("Update device %s locale and language from ActivityManager.", deviceId);
      return true;
    } catch (MobileHarnessException ex) {
      logger.atWarning().log(
          "Update device %s Locale and Language fails: %s",
          deviceId, MoreThrowables.shortDebugString(ex, 0));
      return false;
    }
  }

  /** If language and region are not empty, locale = language-region. Return true if success. */
  private boolean updateLocaleDimensionFromLanguageRegion() {
    ImmutableSet<AndroidProperty> keySet =
        ImmutableSet.of(AndroidProperty.LANGUAGE, AndroidProperty.REGION);
    String loc = "";
    for (AndroidProperty key : keySet) {
      ImmutableSet<String> values =
          ImmutableSet.copyOf(device.getDimension(Ascii.toLowerCase(key.name())));
      if (values != null && !values.isEmpty()) {
        if (loc.length() > 0) {
          loc += '-';
        }
        loc += Iterables.get(values, 0);
      }
    }
    if (loc.matches(PROPERTY_NAME_LOCALE_PATTERN)) {
      updateDimension(AndroidProperty.LOCALE, loc);
      logger.atInfo().log("Update device %s locale from language and region.", deviceId);
      return true;
    }
    return false;
  }

  /**
   * Checks the device and updates device dimension if needed.
   *
   * @return {@code true} if any device dimension is changed.
   */
  public boolean checkDevice() throws MobileHarnessException, InterruptedException {
    updateCustomizedDimensions();

    // Update GMS version in the dimension because specific lab may update gms version when reset
    // the device.
    boolean isDimensionChanged = updateGMSVersionDimensions();

    // Update system property dimension in case some property changed.
    isDimensionChanged = updateAndroidPropertyDimensions(deviceId) || isDimensionChanged;
    if (device.getIntegerProperty(PROPERTY_NAME_CACHED_SDK_VERSION).isEmpty()) {
      return device.removeDimension(Ascii.toLowerCase(AndroidProperty.SDK_VERSION.name()))
          || isDimensionChanged;
    } else if (device.getIntegerProperty(PROPERTY_NAME_CACHED_SDK_VERSION).get() >= 18) {
      return updateGServicesAndroidID(deviceId) || isDimensionChanged;
    }
    return isDimensionChanged;
  }

  /**
   * Set up the Android device before running the test.
   *
   * @param testInfo the test info
   * @param isRooted if the device is rooted
   */
  public void preRunTest(TestInfo testInfo, boolean isRooted)
      throws MobileHarnessException, InterruptedException {
    try {
      boolean enableDexPreVerification =
          testInfo.jobInfo().params().isTrue(PARAM_DEX_PRE_VERIFICATION);

      if (isRooted) {
        // Set dex pre-verification only for rooted device.
        androidSystemSettingUtil.setDexPreVerification(deviceId, enableDexPreVerification);
        testInfo
            .log()
            .atInfo()
            .alsoTo(logger)
            .log(
                "%s device %s dex pre-verification",
                enableDexPreVerification ? "Enabled" : "Disabled", deviceId);
      }

      stopUnexpectedProcessOnDevice(testInfo);
      // Before test start, clear the logcat from last run.
      // Keep this clearLog unified to avoid conflict from multiple callers in decorators.
      androidAdbUtil.clearLog(deviceId);
    } catch (MobileHarnessException e) {
      testInfo
          .errors()
          .addAndLog(
              new MobileHarnessException(
                  AndroidErrorId.ANDROID_DEVICE_DELEGATE_TEST_PREP_ERROR, e.getMessage(), e),
              logger);
    }
  }

  /** Returns the cached ABI of the device. */
  public Optional<String> getCachedAbi() {
    return Optional.ofNullable(device.getProperty(PROPERTY_NAME_CACHED_ABI));
  }

  /** Returns the cached sdk version of the device. */
  public Optional<Integer> getCachedSdkVersion() {
    return device.getIntegerProperty(PROPERTY_NAME_CACHED_SDK_VERSION);
  }

  /** Returns the cached screen density of this device. */
  public Optional<Integer> getCachedScreenDensity() {
    return device.getIntegerProperty(PROPERTY_NAME_CACHED_SCREEN_DENSITY);
  }

  /** Updates the cached sdk version of the device. */
  public void updateCachedSdkVersion() throws MobileHarnessException, InterruptedException {
    device.setProperty(
        PROPERTY_NAME_CACHED_SDK_VERSION,
        Integer.toString(androidSystemSettingUtil.getDeviceSdkVersion(deviceId)));
  }

  /**
   * Updates the dimensions "gservices_android_id".
   *
   * @return true iff its value has changed
   */
  public boolean updateGServicesAndroidID(String deviceId) throws InterruptedException {
    if (device.getIntegerProperty(PROPERTY_NAME_CACHED_SDK_VERSION).orElse(0) < 18) {
      logger.atWarning().log("GService Android ID is not available below API level 18.");
      return false;
    }
    boolean isUpdated = false;
    try {
      String gservicesAndroidId = sqlite.getGServicesAndroidID(deviceId);
      logger.atInfo().log("Got device %s GService Android ID: %s", deviceId, gservicesAndroidId);
      isUpdated = device.updateDimension(Dimension.Name.GSERVICES_ANDROID_ID, gservicesAndroidId);
    } catch (com.google.wireless.qa.mobileharness.shared.MobileHarnessException e) {
      logger.atWarning().log(
          "Failed to get device %s GService Android ID: %s",
          deviceId, MoreThrowables.shortDebugString(e, 0));
    }
    return isUpdated;
  }

  /**
   * Stop unexpected process which may left from previous test It won't hurt anything if the process
   * is not there on device
   */
  private void stopUnexpectedProcessOnDevice(TestInfo testInfo)
      throws MobileHarnessException, InterruptedException {
    int sdkVersion = androidSystemSettingUtil.getDeviceSdkVersion(deviceId);
    for (String packageName : ANDROID_UNEXPECTED_PACKAGE) {
      testInfo
          .log()
          .atInfo()
          .alsoTo(logger)
          .log("Stop package on device %s before test started: %s", deviceId, packageName);
      androidProcessUtil.stopApplication(
          UtilArgs.builder().setSerial(deviceId).setSdkVersion(sdkVersion).build(), packageName);
    }
  }

  /**
   * Adds all the system properties to its dimensions. If a property not found, will not add the
   * property the dimensions.
   */
  @VisibleForTesting
  boolean updateAndroidPropertyDimensions(String deviceId)
      throws MobileHarnessException, InterruptedException {
    boolean isDimensionChanged = false;
    for (AndroidProperty key : AndroidProperty.values()) {
      if (PROP_NOT_SET_AS_DIMENSION.contains(key)) {
        continue;
      }
      ImmutableSet<String> oldValues =
          ImmutableSet.copyOf(device.getDimension(Ascii.toLowerCase(key.name())));
      String value = getPropertyValue(deviceId, key);
      if (!value.isEmpty()) {
        ImmutableSet<String> values = maybeLowerCaseProperty(key, value);
        if (!oldValues.equals(values)) {
          logger.atInfo().log(
              "Dimension %s=%s (was: %s), device_id=%s",
              Ascii.toLowerCase(key.name()), values, oldValues, deviceId);
          device.updateDimension(Ascii.toLowerCase(key.name()), values.toArray(new String[0]));
          isDimensionChanged = true;
        }
      }

      switch (key) {
        case ABI:
          device.setProperty(PROPERTY_NAME_CACHED_ABI, value);
          break;
        case RELEASE_VERSION:
          // Expose major version as a dimension, as many clients do not care about minor version.
          String majorVersion = extractMajorVersionFromFullVersion(value);
          logger.atInfo().log(
              "Dimension %s=%s, device_id=%s",
              Ascii.toLowerCase(Dimension.Name.RELEASE_VERSION_MAJOR.toString()),
              Ascii.toLowerCase(majorVersion),
              deviceId);
          device.updateDimension(
              Dimension.Name.RELEASE_VERSION_MAJOR, Ascii.toLowerCase(majorVersion));
          break;
        case SCREEN_DENSITY:
          try {
            device.setProperty(
                PROPERTY_NAME_CACHED_SCREEN_DENSITY, Integer.toString(Integer.parseInt(value)));
          } catch (NumberFormatException e) {
            logger.atWarning().log(
                "Failed to parse device %s screen density '%s' from device property: %s",
                deviceId, value, MoreThrowables.shortDebugString(e, 0));
          }
          break;
        case SDK_VERSION:
          try {
            device.setProperty(
                PROPERTY_NAME_CACHED_SDK_VERSION, Integer.toString(Integer.parseInt(value)));
          } catch (NumberFormatException e) {
            logger.atWarning().log(
                "Failed to parse device %s sdk version '%s' from device property: %s",
                deviceId, value, MoreThrowables.shortDebugString(e, 0));
          }
          break;
        default:
          break;
      }
    }

    return isDimensionChanged;
  }

  /**
   * Returns an ImmutableSet containing either a lower-cased value or if the {@link AndroidProperty}
   * should be retained (see {@link #RETAIN_CAPS_PROPERTIES}), a set containing at most two Strings:
   * 1) value unchanged, and 2) a lower-cased value.
   */
  private ImmutableSet<String> maybeLowerCaseProperty(AndroidProperty key, String value) {
    ImmutableSet.Builder<String> valuesBuilder = ImmutableSet.<String>builder();
    if (RETAIN_CAPS_PROPERTIES.contains(key)) {
      valuesBuilder.add(value);
    }
    valuesBuilder.add(Ascii.toLowerCase(value));
    return valuesBuilder.build();
  }

  /**
   * Extracts first two elements and returns them dot-separated, from a 3-element version string.
   * Example: "6.0.1" -> "6.0". If the input does not contain two dot characters, returns the
   * argument unchanged.
   */
  private String extractMajorVersionFromFullVersion(String fullVersion) {
    // Finding a character is faster than parsing regular expressions.
    int firstDotIndex = fullVersion.indexOf('.');
    if (firstDotIndex != -1) {
      int secondDotIndex = fullVersion.indexOf('.', firstDotIndex + 1);
      if (secondDotIndex > firstDotIndex && secondDotIndex < fullVersion.length() - 1) {
        return fullVersion.substring(0, secondDotIndex);
      }
    }
    return fullVersion;
  }

  /** Gets property value. */
  public String getPropertyValue(String deviceId, AndroidProperty key)
      throws MobileHarnessException, InterruptedException {
    return androidAdbUtil.getProperty(deviceId, key);
  }

  /**
   * Removes the customized dimension if it is set as default value.
   *
   * @return whether the customized dimension needs to be updated.
   */
  private boolean checkCustomizedDimension(Dimension.Name name) {
    List<String> customizedDimension = device.getDimension(name);
    if (customizedDimension.isEmpty()) {
      return false;
    }
    // User has deleted the dimension from config file
    if (customizedDimension.size() == 1
        && !customizedDimension.get(0).equals(Dimension.Value.CUSTOMIZED_DEFAULT)) {
      device.removeDimension(name);
      return false;
    }
    return true;
  }

  /**
   * Updates dimensions related to application version on device.
   *
   * @return whether the application version is changed.
   */
  private boolean updateAppVersion(String deviceId, Dimension.Name name, String packageName)
      throws InterruptedException {
    String versionName = "";
    try {
      versionName = androidPackageManagerUtil.getAppVersionName(deviceId, packageName);
    } catch (MobileHarnessException e) {
      logger.atInfo().log("Application %s version not found on device %s", packageName, deviceId);
    }
    // if the application is not installed, the versionName could be NULL.
    // Remove the dimension.
    if (versionName == null) {
      return device.removeDimension(name);
    }
    if (device.updateDimension(name, versionName)) {
      logger.atInfo().log(
          "Update dimension %s to: %s, device_id=%s",
          Ascii.toLowerCase(name.name()), versionName, deviceId);
      return true;
    }
    return false;
  }

  private void updateCustomizedDimensions() throws InterruptedException {
    if (checkCustomizedDimension(Dimension.Name.AGSA_VERSION)) {
      updateAppVersion(
          deviceId, Dimension.Name.AGSA_VERSION, AndroidPackages.AGSA.getPackageName());
    }
    if (checkCustomizedDimension(Dimension.Name.CHROME_VERSION)) {
      updateAppVersion(
          deviceId, Dimension.Name.CHROME_VERSION, AndroidPackages.CHROME.getPackageName());
    }
  }

  private boolean updateGMSVersionDimensions() throws InterruptedException {
    return updateAppVersion(
        deviceId, Dimension.Name.GMS_VERSION, AndroidPackages.GMS.getPackageName());
  }

  /** List of decorators/drivers that should be supported by root/non-root devices. */
  public void basicAndroidDeviceConfiguration(boolean isRooted)
      throws MobileHarnessException, InterruptedException {

    // Checks root.
    device.addDimension(DIMENSION_NAME_ROOTED, String.valueOf(isRooted));

    // Adds general drivers.
    device.addSupportedDriver("AndroidChopin");
    device.addSupportedDriver("AndroidGUnit");
    device.addSupportedDriver("AndroidHawkeyeBaselineExperiment");
    device.addSupportedDriver("AndroidInstrumentation");
    device.addSupportedDriver("AndroidMarmosetDriver");
    device.addSupportedDriver("AndroidMonkey");
    device.addSupportedDriver("AndroidNativeBin");
    device.addSupportedDriver("AndroidNuwa");
    device.addSupportedDriver("AndroidUIAutomator");
    device.addSupportedDriver("FlutterDriver");
    device.addSupportedDriver("ManekiTest");
    device.addSupportedDriver("VegaTest");
    device.addSupportedDriver("YtsTest");
    device.addSupportedDriver("MoblyTest");
    device.addSupportedDriver("VinsonDriver");
    device.addSupportedDriver("NoOpDriver");
    device.addSupportedDriver("AndroidCopycatRemoteControlledMoblySnippetTest");

    basicAndroidDecoratorConfiguration();
  }

  /** List of decorators that should be supported by root/non-root devices. */
  public void basicAndroidDecoratorConfiguration() throws InterruptedException {
    // Adds general decorators.
    device.addSupportedDecorator("AndroidAccountDecorator");
    device.addSupportedDecorator("AndroidAdbShellDecorator");
    device.addSupportedDecorator("AndroidAppVersionDecorator");
    device.addSupportedDecorator("AndroidBugreportDecorator");
    device.addSupportedDecorator("AndroidChopinDecorator");
    device.addSupportedDecorator("AndroidChromeWebViewInstallerDecorator");
    device.addSupportedDecorator("AndroidCleanAppsDecorator");
    device.addSupportedDecorator("AndroidCrashMonitorDecorator");
    device.addSupportedDecorator("AndroidDisableAppsDecorator");
    device.addSupportedDecorator("AndroidDumpSysDecorator");
    device.addSupportedDecorator("AndroidFilePullerDecorator");
    device.addSupportedDecorator("AndroidFilePusherDecorator");
    device.addSupportedDecorator("AndroidForceInstallGmsCoreDecorator");
    device.addSupportedDecorator("AndroidFrameRenderDecorator");
    device.addSupportedDecorator("AndroidGmsCoreConfigDecorator");
    device.addSupportedDecorator("AndroidGmsCoreNetworkStatsDecorator");
    device.addSupportedDecorator("AndroidKillProcessDecorator");
    device.addSupportedDecorator("AndroidInstallAppsDecorator");
    device.addSupportedDecorator("AndroidInstallMultipleApksDecorator");
    device.addSupportedDecorator("AndroidLogCatDecorator");
    device.addSupportedDecorator("AndroidNuwaDecorator");
    device.addSupportedDecorator("AndroidOrientationDecorator");
    device.addSupportedDecorator("AndroidPackageStatsDecorator");
    device.addSupportedDecorator("AndroidResMonitorDecorator");
    device.addSupportedDecorator("AndroidScreenshotDecorator");
    device.addSupportedDecorator("AndroidSdVideoDecorator");
    device.addSupportedDecorator("AndroidSetPropDecorator");
    device.addSupportedDecorator("AndroidStartAppsDecorator");
    device.addSupportedDecorator("AndroidStatsWakeLocksDecorator");
    device.addSupportedDecorator("AndroidSwitchLanguageDecorator");
    device.addSupportedDecorator("AndroidSwitchUserDecorator");
    device.addSupportedDecorator("AndroidSystemHealthMemoryDecorator");
    device.addSupportedDecorator("AndroidPerfettoDecorator");
    device.addSupportedDecorator("AndroidWebviewBridgeDecorator");
    device.addSupportedDecorator("ManekiYouTubeLauncherDecorator");
    device.addSupportedDecorator("ManekiAndroidWebdriverPortForwardDecorator");
    device.addSupportedDecorator("MaskImageDecorator");

    // This will replace `AndroidChromeWebViewInstallerDecorator` when it's ready. DO NOT USE YET!
    device.addSupportedDecorator("AndroidChromeInstallerDecorator");
  }

  /** List of additional decorators/drivers that should be supported by root/non-root devices. */
  private void additionalAndroidDeviceConfiguration() throws InterruptedException {

    // Adds decorators validating sdk version. Not supported by GCE AVD place holders.
    device.addSupportedDecorator("AndroidLocationDecorator");
    device.addSupportedDecorator("NoOpDecorator");
    device.addSupportedDecorator("AndroidDeviceActionInstallMainlineDecorator");
    device.addSupportedDecorator("AndroidDeviceActionResetDecorator");
    device.addSupportedDecorator("AndroidHermeticServerLauncherDecorator");
    device.addSupportedDecorator("AndroidHermeticWiredHttpProxyDecorator");
    device.addSupportedDecorator("AndroidLazyProxyDecorator");
    device.addSupportedDecorator("AndroidInstallMainlineModulesDecorator");

    // *********************************************************************************************
    // The following features are only enabled in full stack labs or Local Mode.
    // *********************************************************************************************

    if (!ifEnableFullStackFeatures()) {
      return;
    }

    // More Drivers.
    device.addSupportedDriver("AndroidChromedriver");
    device.addSupportedDriver("MoblyAospTest");

    // More Decorators.
    device.addSupportedDecorator("AndroidCreateWorkProfileDecorator");
  }

  /** List of decorators/drivers that should be supported by rooted devices. */
  private void rootedAndroidDeviceConfiguration(String deviceId) throws InterruptedException {

    // Advanced device settings. This decorator is only full tested on AndroidRealDevice. Some
    // settings may not be supported for emulators.
    device.addSupportedDecorator("AndroidDeviceSettingsDecorator");

    device.addSupportedDecorator("AndroidDisableAutoUpdatesDecorator");
    device.addSupportedDecorator("AndroidHermeticServerCertPusherDecorator");
    device.addSupportedDecorator("AndroidInstallAppBundleDecorator");
    device.addSupportedDecorator("AndroidInstallSystemAppsDecorator");
    device.addSupportedDecorator("AndroidPermissionDecorator");
    device.addSupportedDecorator("AndroidReinstallSystemAppsDecorator");

    // *********************************************************************************************
    // The following features are only enabled in full stack labs or Local Mode.
    // *********************************************************************************************

    if (!ifEnableFullStackFeatures()) {
      return;
    }

    // More Drivers.
    device.addSupportedDriver("HostBin");

    // Gets GServices Android ID(go/android-id).
    if (device.getIntegerProperty(PROPERTY_NAME_CACHED_SDK_VERSION).orElse(0) >= 18) {
      updateGServicesAndroidID(deviceId);
    }

    // Gets the version of Google play service on the device.
    try {
      String version =
          androidPackageManagerUtil.getAppVersionName(deviceId, PackageConstants.PACKAGE_NAME_GMS);
      if (!Strings.isNullOrEmpty(version)) {
        logger.atInfo().log("Got device %s GMS version: %s", deviceId, version);
        device.addDimension(Dimension.Name.GMS_VERSION, version);
      }
    } catch (MobileHarnessException e) {
      logger.atWarning().log(
          "Failed to get device %s GMS version: %s",
          deviceId, MoreThrowables.shortDebugString(e, 0));
    }

    // Gets the current override size of the screen of the device.
    try {
      ScreenResolution screenResolution = androidSystemSettingUtil.getScreenResolution(deviceId);
      logger.atInfo().log("Device %s screen resolution: %s", deviceId, screenResolution);
      device.addDimension(
          Dimension.Name.SCREEN_SIZE,
          String.format("%sx%s", screenResolution.curWidth(), screenResolution.curHeight()));
    } catch (MobileHarnessException e) {
      logger.atWarning().log(
          "Failed to get screen size for device %s: %s",
          deviceId, MoreThrowables.shortDebugString(e, 0));
    }
  }

  /**
   * Whether it will enable full stack features for the lab.
   *
   * <p>Subclass can override it to decide whether enable full statck features.
   */
  protected abstract boolean ifEnableFullStackFeatures();
}
