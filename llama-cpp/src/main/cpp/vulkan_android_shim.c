// The NDK libvulkan stub is pinned to the app's minSdk (API 26), whose loader
// predates the Vulkan 1.1 entry points. ggml-vulkan calls
// vkGetPhysicalDeviceFeatures2 directly, so the link would fail. Resolve it
// from the runtime loader instead, and fall back to the 1.0 query on devices
// whose loader is still 1.0.
#include <dlfcn.h>
#include <stddef.h>
#include <vulkan/vulkan.h>

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(
        VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2 *pFeatures) {
    static PFN_vkGetPhysicalDeviceFeatures2 pfn;
    static int resolved;

    if (!resolved) {
        void *handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (handle != NULL) {
            pfn = (PFN_vkGetPhysicalDeviceFeatures2)dlsym(handle, "vkGetPhysicalDeviceFeatures2");
        }
        resolved = 1;
    }

    if (pfn != NULL) {
        pfn(physicalDevice, pFeatures);
        return;
    }
    if (pFeatures == NULL) {
        return;
    }
    // No 1.1 loader: report the core 1.0 features and leave the chained
    // structures as the caller initialised them.
    vkGetPhysicalDeviceFeatures(physicalDevice, &pFeatures->features);
}
