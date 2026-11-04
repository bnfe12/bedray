#pragma once
#ifdef VENDOR_TEST_BACKEND_BUILD
#define TEST_API extern "C" __declspec(dllexport)
#else
#define TEST_API extern "C" __declspec(dllimport)
#endif
enum TestOperation { OpAllocate = 1, OpCreate, OpEvaluate, OpRelease, OpDestroyParameters, OpShutdown, OpTag, OpOptions, OpCaps };
enum TestKind { KindAny = -1, KindSr = 0, KindRr = 1 };
// Reset counters/fault injection only after the wrapper acknowledged shutdown.
TEST_API void vendor_test_reset();
TEST_API void vendor_test_fail(int operation, int count, int partial);
TEST_API int vendor_test_count(int operation, int kind);
TEST_API int vendor_test_live_features(int kind);
TEST_API int vendor_test_live_parameters();
TEST_API int vendor_test_last_feature();
TEST_API int vendor_test_last_viewport();
TEST_API void vendor_test_caps_available(int available);
