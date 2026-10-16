import { NativeModule, registerWebModule } from "expo";
import type { PermissionResponse } from "expo-modules-core";

import type {
  ExpoSpeechRecognitionModuleType,
  ExpoSpeechRecognitionNativeEvents,
  ExpoSpeechRecognitionOptions,
  ExpoSpeechRecognitionPermissionResponse,
  SetCategoryOptions,
} from "./ExpoSpeechRecognitionModule.types";

// Windows has no native speech recognition: speech-to-text there goes through Sherpa. This
// stand-in keeps the module importable and fails each call with `ERR_UNSUPPORTED`; async methods
// reject. Listeners can still be added (the STT service subscribes up front), but no events are
// ever emitted.

function unsupported(feature: string): Error {
  return Object.assign(
    new Error(
      `Native speech recognition (${feature}) is not supported on Windows. Use Sherpa instead.`,
    ),
    { code: "ERR_UNSUPPORTED" },
  );
}

class ExpoSpeechRecognitionModuleWindows
  extends NativeModule<ExpoSpeechRecognitionNativeEvents>
  implements ExpoSpeechRecognitionModuleType
{
  start(_options: ExpoSpeechRecognitionOptions): void {
    throw unsupported("start");
  }

  stop(): void {
    throw unsupported("stop");
  }

  abort(): void {
    throw unsupported("abort");
  }

  async requestPermissionsAsync(): Promise<ExpoSpeechRecognitionPermissionResponse> {
    throw unsupported("requestPermissionsAsync");
  }

  async getPermissionsAsync(): Promise<ExpoSpeechRecognitionPermissionResponse> {
    throw unsupported("getPermissionsAsync");
  }

  async getMicrophonePermissionsAsync(): Promise<PermissionResponse> {
    throw unsupported("getMicrophonePermissionsAsync");
  }

  async requestMicrophonePermissionsAsync(): Promise<PermissionResponse> {
    throw unsupported("requestMicrophonePermissionsAsync");
  }

  async getSpeechRecognizerPermissionsAsync(): Promise<ExpoSpeechRecognitionPermissionResponse> {
    throw unsupported("getSpeechRecognizerPermissionsAsync");
  }

  async requestSpeechRecognizerPermissionsAsync(): Promise<ExpoSpeechRecognitionPermissionResponse> {
    throw unsupported("requestSpeechRecognizerPermissionsAsync");
  }

  async getSupportedLocales(_options: {
    androidRecognitionServicePackage?: string;
  }): Promise<{ locales: string[]; installedLocales: string[] }> {
    throw unsupported("getSupportedLocales");
  }

  getSpeechRecognitionServices(): string[] {
    throw unsupported("getSpeechRecognitionServices");
  }

  getDefaultRecognitionService(): { packageName: string } {
    throw unsupported("getDefaultRecognitionService");
  }

  getAssistantService(): { packageName: string } {
    throw unsupported("getAssistantService");
  }

  supportsOnDeviceRecognition(): boolean {
    throw unsupported("supportsOnDeviceRecognition");
  }

  supportsRecording(): boolean {
    throw unsupported("supportsRecording");
  }

  isRecognitionAvailable(): boolean {
    throw unsupported("isRecognitionAvailable");
  }

  async androidTriggerOfflineModelDownload(_options: {
    locale: string;
  }): ReturnType<
    ExpoSpeechRecognitionModuleType["androidTriggerOfflineModelDownload"]
  > {
    throw unsupported("androidTriggerOfflineModelDownload");
  }

  setCategoryIOS(_options: SetCategoryOptions): void {
    throw unsupported("setCategoryIOS");
  }

  getAudioSessionCategoryAndOptionsIOS(): ReturnType<
    ExpoSpeechRecognitionModuleType["getAudioSessionCategoryAndOptionsIOS"]
  > {
    throw unsupported("getAudioSessionCategoryAndOptionsIOS");
  }

  setAudioSessionActiveIOS(
    _value: boolean,
    _options?: { notifyOthersOnDeactivation: boolean },
  ): void {
    throw unsupported("setAudioSessionActiveIOS");
  }

  async getStateAsync(): ReturnType<
    ExpoSpeechRecognitionModuleType["getStateAsync"]
  > {
    throw unsupported("getStateAsync");
  }
}

export const ExpoSpeechRecognitionModule = registerWebModule(
  ExpoSpeechRecognitionModuleWindows,
  "ExpoSpeechRecognition",
);
