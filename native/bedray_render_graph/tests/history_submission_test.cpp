#include "bedray/history_submission.hpp"
#include <cstdlib>

using namespace bedray;
using namespace bedray::graph;

static void require(bool condition) { if (!condition) std::abort(); }

static HistoryImageSet images(std::uint64_t first) {
    HistoryImageSet result{};
    result.count = 2;
    result.images[0] = {first, first, 0, 4096};
    result.images[1] = {first + 1, first + 1, 0, 4096};
    return result;
}

static HistoryKey key() { return {1, 1, 1, 1, 1, 1, 64, 64}; }

static void implicit_close_is_unknown() {
    HistoryRegistry registry(65536);
    require(registry.begin_device(1) == RegistryStatus::ok);
    SignalId id{};
    require(registry.add("bedray.test.history", {}, id) == RegistryStatus::ok);
    require(registry.configure(id, images(1)) == RegistryStatus::ok);
    HistoryRecording recording{};
    HistoryStatus detail{};
    require(registry.prepare(id, key(), 1, 0, HistoryOrdering::completed_only, recording, detail) == RegistryStatus::ok);
    {
        HistorySubmission submission(registry);
        require(submission.begin(0, 1) == RegistryStatus::ok);
        require(submission.attach(id, recording.token, true, detail) == RegistryStatus::ok);
        // Simulate a caller forgetting the attempt marker and result. The
        // destructor must not treat the possibly submitted recording as rejected.
    }
    require(registry.timeline().submitted[0] == 1);
    require(registry.configure(id, images(3)) == RegistryStatus::ok);
    RetiredHistoryImages retired{};
    require(!registry.pop_finished(retired));
    require(registry.device_idle() == RegistryStatus::ok);
    require(registry.pop_finished(retired));
}

static void explicit_rejection_releases_serial() {
    HistoryRegistry registry(65536);
    require(registry.begin_device(1) == RegistryStatus::ok);
    SignalId id{};
    require(registry.add("bedray.test.reject", {}, id) == RegistryStatus::ok);
    require(registry.configure(id, images(10)) == RegistryStatus::ok);
    HistoryRecording recording{};
    HistoryStatus detail{};
    require(registry.prepare(id, key(), 1, 0, HistoryOrdering::completed_only, recording, detail) == RegistryStatus::ok);
    {
        HistorySubmission submission(registry);
        require(submission.begin(0, 1) == RegistryStatus::ok);
        require(submission.attach(id, recording.token, true, detail) == RegistryStatus::ok);
        require(submission.finish(SubmitOutcome::rejected) == RegistryStatus::ok);
    }
    require(registry.timeline().submitted[0] == 0);
    require(registry.prepare(id, key(), 1, 0, HistoryOrdering::completed_only, recording, detail) == RegistryStatus::ok);
    require(registry.cancel(id, recording.token, detail) == RegistryStatus::ok);
}

int main() {
    implicit_close_is_unknown();
    explicit_rejection_releases_serial();
}
