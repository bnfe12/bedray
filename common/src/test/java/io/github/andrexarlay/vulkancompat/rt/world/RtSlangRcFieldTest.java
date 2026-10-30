package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry;
import io.github.andrexarlay.vulkancompat.rt.rc.RcReference;
import io.github.andrexarlay.vulkancompat.rt.rc.RcTransport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RtSlangRcFieldTest {
    private static final String SHADERS = "src/main/resources/shaders/slang/";
    private static final String WORLD = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/";

    @Test void compiledBuilderAndTraceDeclareTheirFullIndependentAotContracts() {
        Assumptions.assumeTrue(SlangPassLibrary.available(RtRadianceCascadesContract.BUILD)
            && SlangPassLibrary.available(RtRadianceCascadesContract.TRACE), "RC AOT entries not compiled");
        var builder = SlangPassLibrary.load(RtRadianceCascadesContract.BUILD).orElseThrow().manifest();
        var trace = SlangPassLibrary.load(RtRadianceCascadesContract.TRACE).orElseThrow().manifest();
        assertDoesNotThrow(() -> RtRadianceCascadesContract.requireBuilder(builder));
        assertDoesNotThrow(() -> RtRadianceCascadesContract.requireFieldBindings(trace));
        assertEquals(80, builder.pushConstantBytes()); assertEquals(16, builder.bindings().size());
        assertArrayEquals(new int[] {64, 1, 1}, builder.threadGroupSize());
        assertArrayEquals(new int[] {0,2,3,9,10,16,17,18,20,21,22,23,24,25,34,35},
            builder.bindings().stream().mapToInt(SlangPassManifest.Binding::binding).toArray());
        assertEquals(112, trace.pushConstantBytes()); assertEquals(28, trace.bindings().size());
        assertArrayEquals(new int[] {8, 8, 1}, trace.threadGroupSize());
        assertArrayEquals(new int[] {0,1,2,3,5,9,10,12,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35},
            trace.bindings().stream().mapToInt(SlangPassManifest.Binding::binding).toArray());
        for (var manifest : List.of(builder, trace)) {
            assertEquals(272, manifest.binding("rcField").elementBytes());
            assertEquals(32, manifest.binding("rcTails").elementBytes());
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER, manifest.binding("rcField").descriptorType());
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, manifest.binding("rcTails").descriptorType());
            assertEquals(0, manifest.descriptorSet());
            assertTrue((manifest.requiredCapabilities() & RtPathTraceSlangPass.REQUIRED_CAPABILITIES)
                == RtPathTraceSlangPass.REQUIRED_CAPABILITIES);
        }
    }

    @Test void pureManifestValidationRejectsPushGroupCountAndEveryCriticalBufferMismatch() {
        var valid = builderManifest(80, new int[] {64,1,1}, builderBindings());
        assertDoesNotThrow(() -> RtRadianceCascadesContract.requireBuilder(valid));
        assertThrows(IllegalStateException.class, () -> RtRadianceCascadesContract.requireBuilder(
            builderManifest(79, new int[] {64,1,1}, builderBindings())));
        assertThrows(IllegalStateException.class, () -> RtRadianceCascadesContract.requireBuilder(
            builderManifest(80, new int[] {32,1,1}, builderBindings())));
        var missing = new ArrayList<>(builderBindings()); missing.removeLast();
        assertThrows(IllegalStateException.class, () -> RtRadianceCascadesContract.requireBuilder(builderManifest(80, new int[] {64,1,1}, missing)));
        for (String name : List.of("rcField", "rcTails", "frame", "sections")) {
            for (int mismatch = 0; mismatch < 3; mismatch++) {
                var bindings = new ArrayList<>(builderBindings());
                for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).name().equals(name)) {
                    var old = bindings.get(i);
                    int type = old.descriptorType() == BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        ? BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER : BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                    bindings.set(i, new SlangPassManifest.Binding(name, mismatch == 0 ? 36 : old.binding(),
                        mismatch == 1 ? type : old.descriptorType(), 1, mismatch == 2 ? old.elementBytes() - 1 : old.elementBytes()));
                }
                var bad = builderManifest(80, new int[] {64,1,1}, bindings);
                assertThrows(IllegalStateException.class, () -> RtRadianceCascadesContract.requireBuilder(bad), name);
                if (name.startsWith("rc"))
                    assertThrows(IllegalStateException.class, () -> RtRadianceCascadesContract.requireFieldBindings(bad), name);
            }
        }
    }

    @Test void featureSelectionRequiresBothAotEntriesAndActualNativeSceneOwnership() throws Exception {
        String backend = source(WORLD + "NativePathTraceBackend.java");
        assertTrue(backend.contains("RtConfig.SLANG_RADIANCE_CASCADES && (!RtConfig.SLANG_NATIVE_SCENE"));
        assertTrue(backend.contains("SlangPassLibrary.available(RtRadianceCascadesContract.BUILD)"));
        assertTrue(backend.contains("SlangPassLibrary.available(RtRadianceCascadesContract.TRACE)"));
        assertTrue(backend.contains("RtRadianceCascadesContract.requireBuilder(builder.manifest())"));
        String host = source(WORLD + "BedrayPathTraceNative.java");
        assertTrue(host.contains("rc != null && (input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE) == 0"));
        assertTrue(host.indexOf("library.configurePathTrace(") < host.indexOf("if (rc != null) rc.configure()"));
        assertFalse(backend.contains("new SlangProgram.Resource"));
        assertFalse(backend.contains("vkCmdDispatch"));
        String config = source(WORLD + "RtConfig.java");
        assertTrue(config.contains("System.getProperty(\"vkbridge.rt.radianceCascades\", \"false\")"));
        assertTrue(config.contains("Integer.getInteger(\"vkbridge.rt.rcBaseAxis\", 8)"));
    }

    @Test void nativeRecorderOrdersAllFiveLevelsBeforeThePrimaryFieldConsumer() throws Exception {
        String nativeField = source("../native/bedray_core/src/rc_field_pass.cpp");
        String recorder = body(nativeField, "BedrayStatus RCFieldPass::record(");
        assertTrue(Pattern.compile("for\\s*\\(int c\\s*=\\s*4;\\s*c\\s*>=\\s*0;\\s*--c\\)").matcher(recorder).find());
        String levels = body(recorder, "for (int c=4;c>=0;--c)");
        assertTrue(levels.indexOf("bedray_core_render_frame(") < levels.lastIndexOf("barrier(fns_,cmd)"));
        assertTrue(levels.contains("if (status != BEDRAY_OK)"));
        String path = body(source("../native/bedray_core/src/path_trace_pass.cpp"), "BedrayStatus PathTracePass::render(");
        assertTrue(path.indexOf("rc_field_->record(") < path.indexOf("bedray_core_render_frame(core_, &trace_desc"));
        assertTrue(path.contains("field_bindings && !rc_field_"));
        assertTrue(path.contains("rc_field_ && !native_scene"));
        assertTrue(path.contains("trace_resources[26] = field_resources[0]"));
        assertTrue(path.contains("trace_resources[27] = field_resources[1]"));
    }

    @Test void shiftedGatherRejectsZeroNormalAndNonfiniteInputsBeforeAnyGeometricWeight() throws Exception {
        String gather = compact(source(SHADERS + "radiance_cascades/field_gather.slang"));
        int normalize = gather.indexOf("normal=normalize(normal)");
        for (String guard : List.of("!all(isfinite(position))", "!all(isfinite(normal))", "!(dot(normal,normal)>0.0)",
            "!isfinite(dot(normal,normal))", "!isfinite(rayBias)", "!(rayBias>0.0)", "!isfinite(maxRadiance)", "!(maxRadiance>0.0)"))
            assertTrue(gather.indexOf(guard) >= 0 && gather.indexOf(guard) < normalize, guard);
        assertTrue(gather.indexOf("!all(isfinite(origin))") < gather.indexOf("for(uintn=0u;"));
        assertTrue(gather.contains("if(any(abs(origin-q)>=float3(field.ranges[0].x/sqrt(3.0))))returnresult;"));
        assertTrue(gather.contains("irradiance+=spatialWeight*projected*sample.radiance"));
        assertTrue(gather.contains("result.cosineMean=irradiance*(1.0/3.14159265358979323846)"));
    }

    @Test void rcContinuationHonorsMaxDepthOneAndUnresolvedEvidenceIsDistinctFromNonfiniteFailure() throws Exception {
        String tracer = source(SHADERS + "pathtrace/path_tracer.slang");
        String compact = compact(tracer);
        assertTrue(compact.contains("receiver.primary&&result.depth+1u<options.maxDepth"),
            "at initial depth0 maxDepth1 permits no RC continuation");
        assertTrue(compact.contains("bsdf.lobe==BSDF_LOBE_DIFFUSE&&!medium.inside"));
        assertTrue(compact.contains("(surface.material.sourceFlags&MATERIAL_SOURCE_FLUID)==0u"));
        String invalidField = body(tracer, "if (incoming.status == DIFFUSE_FIELD_INVALID)");
        assertTrue(invalidField.contains("PATH_TRACE_FLAG_RC_UNRESOLVED"));
        assertFalse(invalidField.contains("PATH_TRACE_FLAG_INVALID_SAMPLE"));
        assertTrue(invalidField.contains("break;"));
        String rejected = body(tracer, "bool pathTraceSampleRejected(");
        assertTrue(rejected.contains("PATH_TRACE_FLAG_INVALID_SAMPLE | PATH_TRACE_FLAG_RC_UNRESOLVED"));
        assertTrue(tracer.contains("PATH_TRACE_FLAG_RC_UNRESOLVED    = 1u << 12u"));
        assertTrue(tracer.contains("PATH_TRACE_FLAG_INVALID_SAMPLE   = 1u << 3u"));
        String entry = source(SHADERS + "pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("bool sampleTrusted = !pathTraceSampleRejected(result.flags);"));
        assertTrue(entry.contains("sampleTrusted ? 1.0 : 0.0"));
        int outside = tracer.indexOf("result.flags |= PATH_TRACE_FLAG_RC_OUTSIDE;");
        int ordinary = tracer.indexOf("throughput *= bsdf.weight;", outside);
        assertTrue(outside >= 0 && ordinary > outside);
        assertFalse(tracer.substring(outside, ordinary).contains("break;"), "outside support continues the ordinary sampled ray");
        assertTrue(tracer.contains("throughput * bsdf.weight * incoming.cosineMean"));
    }

    @Test void aTiltedShadingNormalCannotMoveTheGatherOriginBehindItsGeometricFace() throws Exception {
        var geometric = new RcGeometry.Vec3(0,1,0);
        var shading = new RcGeometry.Vec3(0.99,-0.1,0).normalized();
        var view = new RcGeometry.Vec3(1,0.2,0).normalized();
        assertTrue(shading.dot(view)>0, "the normal map still faces the viewer");
        assertTrue(shading.scale(0.001).dot(geometric)<0, "shading-normal offset enters the triangle's back side");
        assertEquals(0.001,geometric.scale(0.001).dot(geometric),1e-12);
        String tracer=source(SHADERS+"pathtrace/path_tracer.slang");
        String gather=source(SHADERS+"radiance_cascades/field_gather.slang");
        assertTrue(tracer.contains("safeNormalize(surface.hit.geometricNormal, normal)"));
        assertTrue(tracer.contains("if (dot(fieldOffsetNormal, view) < 0.0)"));
        assertTrue(gather.contains("position + normalize(offsetNormal) * rayBias"));
        assertTrue(gather.contains("rcFieldProjectedArea(corners, normal)"), "radiometry still uses the shading normal");
    }

    @Test void endpointOwnershipCompletenessAndConfidenceStayIndependentOfSkyAndQuadrature() throws Exception {
        String transport = compact(source(SHADERS + "radiance_cascades/field_transport.slang"));
        assertTrue(transport.contains("ray.nearDistance=0.0"));
        assertTrue(transport.contains("ray.farDistance=asfloat(asuint(distance)-1u)"));
        assertTrue(transport.contains("options.environmentPolicy=uint(RCEnvironmentPolicy.Disabled)"));
        assertTrue(transport.contains("options.worldBoundaryDistance=0.0"));
        assertTrue(transport.contains("rcFieldAverageAdd(average,rcFieldCompose(front,back),weight)"));
        assertTrue(transport.contains("a.confidence=min(a.confidence,s.confidence)"));
        assertFalse(transport.contains("weight*s.confidence"));
        assertTrue(transport.contains("abs(a.weight-1.0)>1.0e-4"));
        assertTrue(transport.contains("!rcSegmentValid(s)"));
        assertTrue(transport.contains("all(result.transmittance==float3(0.0))"));
        assertTrue(transport.contains("RC_FIELD_TERMINAL_KNOWN"));
        String mapping = compact(source(SHADERS + "radiance_cascades/field_mapping.slang"));
        assertTrue(mapping.contains("normalize(lerp(a,b,da/(da-db)))"));
        assertTrue(mapping.contains("atan2(sine,dot(a,b))/sine"));
        assertTrue(mapping.contains("max(0.5*dot(normal,area),0.0)"));
    }

    @Test void primaryNeeOwnsDirectEmissionAndTheFieldKeepsOnlyReflectedIndirectLight() throws Exception {
        String transport = body(source(SHADERS + "radiance_cascades/field_transport.slang"), "RCSegment rcFieldTrace<");
        int suppress = transport.indexOf("materialOptions.emissionScale = 0.0;");
        assertTrue(suppress >= 0 && suppress < transport.indexOf("traceCascadeRay("));
        assertFalse(transport.contains("emissionMask ="), "self-emitter rejection keeps its authored mask");
        String shade = source(SHADERS + "radiance_cascades/rc_trace_shade.slang");
        assertTrue(shade.contains("lights.sample(lightReceiver(surface.hit.position, normal, surface.material.emissionMask)"));
        assertTrue(shade.contains("radiance += contribution;"), "reflected NEE still supplies one indirect bounce");
        assertFalse(source(SHADERS + "radiance_cascades/rc_trace_walk.slang").contains("emissionScale = 0.0"),
            "the new field policy does not alter the v1 interval walker");
        assertFalse(source(SHADERS + "light/block_emitters.slang").contains("emissionScale"));

        var grid = new RcGeometry.Grid(8, RcGeometry.ZERO);
        var directEmitter = new RcGeometry.Vec3(12, 8, 4);
        var reflectedNee = new RcGeometry.Vec3(3, 2, 1);
        var albedo = new RcGeometry.Vec3(0.5, 0.5, 0.5);
        var indirect = RcReference.gather(grid, RcGeometry.ZERO, RcGeometry.ONE,
            bridge -> RcTransport.finiteMiss(), (level, probe, cell) -> RcTransport.interval(reflectedNee, RcGeometry.ZERO, 1));
        var zeroBounce = RcReference.gather(grid, RcGeometry.ZERO, RcGeometry.ONE,
            bridge -> RcTransport.finiteMiss(), (level, probe, cell) -> RcTransport.interval(directEmitter.scale(0), RcGeometry.ZERO, 1));
        assertTrue(indirect.valid() && zeroBounce.valid());
        assertEquals(RcGeometry.ZERO, zeroBounce.value(), "an emitter hit remains an opaque blocker but adds no direct RC term");
        var result = directEmitter.multiply(albedo).add(indirect.value().multiply(albedo).scale(1 / Math.PI));
        var expected = directEmitter.add(reflectedNee).multiply(albedo);
        assertEquals(expected.x(), result.x(), 3e-15);
        assertEquals(expected.y(), result.y(), 3e-15);
        assertEquals(expected.z(), result.z(), 3e-15);
        assertNotEquals(directEmitter.scale(2).add(reflectedNee).multiply(albedo), result,
            "primary NEE plus a second copy of direct emitter visibility would double count energy");
    }

    private static List<SlangPassManifest.Binding> builderBindings() {
        int storage = BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER;
        int uniform = BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        int image = BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        return List.of(binding("tlas",0,BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,0),
            binding("sections",2,storage,64), binding("albedoAtlas",3,image,0), binding("materialAtlas",9,image,0),
            binding("normalAtlas",10,image,0), binding("frame",16,uniform,608), binding("blockMaterials",17,storage,64),
            binding("emitters",18,storage,96), binding("entityPositions",20,storage,4), binding("entityIndices",21,storage,4),
            binding("entityAttrs",22,storage,16), binding("entityRects",23,storage,16), binding("entityTexels",24,storage,4),
            binding("entityPrevious",25,storage,4), binding("rcField",34,uniform,272), binding("rcTails",35,storage,32));
    }
    private static SlangPassManifest.Binding binding(String name, int number, int type, int size) {
        return new SlangPassManifest.Binding(name,number,type,1,size);
    }
    private static SlangPassManifest builderManifest(int push, int[] group, List<SlangPassManifest.Binding> bindings) {
        return new SlangPassManifest(1,"rc_field_build_scene","fixture","","main","compute","sm_6_6",0,group,
            push,1,RtPathTraceSlangPass.REQUIRED_CAPABILITIES,List.of(),bindings);
    }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static String source(String path) throws Exception { return Files.readString(Path.of(path)).replace("\r\n", "\n"); }
    private static String body(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        while (end < source.length() && depth > 0) {
            char next = source.charAt(end++);
            if (next == '{') depth++; else if (next == '}') depth--;
        }
        assertTrue(open >= 0 && depth == 0, marker);
        return source.substring(open + 1, end - 1);
    }
}
