/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "GCImpl.hpp"

#include "GC.hpp"
#include "ThreadSuspension.hpp"

using namespace kotlin;

namespace {

ALWAYS_INLINE void SafePointRegular(gc::GC::ThreadData& threadData, size_t weight) noexcept {
    threadData.impl().gcScheduler().OnSafePointRegular(weight);
    mm::SuspendIfRequested();
}

} // namespace

gc::GC::ThreadData::ThreadData(GC& gc, mm::ThreadData& threadData) noexcept : impl_(make_unique<Impl>(gc, threadData)) {}

gc::GC::ThreadData::~ThreadData() = default;

ALWAYS_INLINE void gc::GC::ThreadData::SafePointFunctionPrologue() noexcept {
    SafePointRegular(*this, GCSchedulerThreadData::kFunctionPrologueWeight);
}

ALWAYS_INLINE void gc::GC::ThreadData::SafePointLoopBody() noexcept {
    SafePointRegular(*this, GCSchedulerThreadData::kLoopBodyWeight);
}

void gc::GC::ThreadData::ScheduleAndWaitFullGC() noexcept {
    impl_->gc().ScheduleAndWaitFullGC();
}

void gc::GC::ThreadData::ScheduleAndWaitFullGCWithFinalizers() noexcept {
    impl_->gc().ScheduleAndWaitFullGCWithFinalizers();
}

void gc::GC::ThreadData::Publish() noexcept {
    impl_->objectFactoryThreadQueue().Publish();
}

void gc::GC::ThreadData::ClearForTests() noexcept {
    impl_->objectFactoryThreadQueue().ClearForTests();
}

ALWAYS_INLINE ObjHeader* gc::GC::ThreadData::CreateObject(const TypeInfo* typeInfo) noexcept {
    return impl_->objectFactoryThreadQueue().CreateObject(typeInfo);
}

ALWAYS_INLINE ArrayHeader* gc::GC::ThreadData::CreateArray(const TypeInfo* typeInfo, uint32_t elements) noexcept {
    return impl_->objectFactoryThreadQueue().CreateArray(typeInfo, elements);
}

void gc::GC::ThreadData::OnStoppedForGC() noexcept {
    impl_->gcScheduler().OnStoppedForGC();
}

gc::GC::GC() noexcept : impl_(make_unique<Impl>()) {}

gc::GC::~GC() = default;

// static
size_t gc::GC::GetAllocatedHeapSize(ObjHeader* object) noexcept {
    return mm::ObjectFactory<GCImpl>::GetAllocatedHeapSize(object);
}

gc::GCSchedulerConfig& gc::GC::gcSchedulerConfig() noexcept {
    return impl_->gcScheduler().config();
}

void gc::GC::ClearForTests() noexcept {
    impl_->objectFactory().ClearForTests();
}
