# Everything builds in Docker (see Dockerfile); results land in build/.
# The host's debug keystore, when present, keeps the APK signature stable across builds.
ifneq ($(wildcard $(HOME)/.android/debug.keystore),)
secret := --secret id=debug_keystore,src=$(HOME)/.android/debug.keystore
endif
# The NDK and SDK tools are x86_64-only, so the images are linux/amd64 on any host.
docker_build := docker build --platform linux/amd64
# ABIS="arm64-v8a" builds the engine for one ABI only (default: both).
ifneq ($(ABIS),)
docker_build += --build-arg ABIS="$(ABIS)"
endif

.PHONY: all apk install plugin jnilibs src clean

all:
	$(docker_build) $(secret) --output build .

apk:
	$(docker_build) $(secret) --target apk-out --output build .

install: all
	adb install -r build/app-release.apk

plugin:
	$(docker_build) --target plugin-out --output build/kiosk-plugin .

jnilibs:
	$(docker_build) --target jnilibs --output build/jniLibs .

src:
	$(docker_build) --target src --output build/src .

clean:
	rm -rf build
