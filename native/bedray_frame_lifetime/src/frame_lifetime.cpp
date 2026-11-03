#include "bedray/frame_lifetime.hpp"

#include <exception>
#include <utility>

namespace bedray {

bool FrameLifetime::retire(Serial last_use, Releaser release) {
    if (!release) return false;
    if (last_use <= completed_) {
        release();
        return true;
    }
    pending_.push_back(Pending{last_use, std::move(release)});
    return true;
}

bool FrameLifetime::complete(Serial completed) {
    if (completed < completed_) return false;
    std::size_t ready_count = 0;
    for (const auto& entry : pending_) {
        if (entry.last_use <= completed) ++ready_count;
    }
    if (ready_count == 0) {
        completed_ = completed;
        return true;
    }

    // Allocate before changing state: an allocation failure must not advance
    // completed_ while leaving a partly rearranged retirement queue behind.
    std::vector<Releaser> ready;
    ready.reserve(ready_count);
    std::size_t keep = 0;
    for (std::size_t i = 0; i < pending_.size(); ++i) {
        auto& entry = pending_[i];
        if (entry.last_use <= completed) {
            ready.push_back(std::move(entry.release));
        } else {
            if (keep != i) pending_[keep] = std::move(entry);
            ++keep;
        }
    }
    pending_.erase(pending_.begin() + static_cast<std::ptrdiff_t>(keep), pending_.end());
    completed_ = completed;

    // Remove entries before callbacks: a callback may re-enter or throw.
    std::exception_ptr first_failure;
    for (auto& release : ready) {
        try {
            release();
        } catch (...) {
            if (!first_failure) first_failure = std::current_exception();
        }
    }
    if (first_failure) std::rethrow_exception(first_failure);
    return true;
}

} // namespace bedray
