public module nort_traversal;
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/geometry.slang";
import "nort/materials.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    Hit trace(float3 origin, float3 direction, float minT, float maxT) {
        int3 size=int3(scene.dimensions().xyz);
        int3 minimum=int3(boundsMinimum()),maximum=minimum+size;
        float enter=-3.402823466e38, leave=maxT;
        float3 enterNormal=float3(0);
        for (int a=0; a<3; a++) {
            if (direction[a]==0.0) {
                if (origin[a]<float(minimum[a]) || origin[a]>=float(maximum[a])) return miss();
            } else {
                float t0=(float(minimum[a])-origin[a])/direction[a], t1=(float(maximum[a])-origin[a])/direction[a];
                float nearT=min(t0,t1), farT=max(t0,t1);
                if (nearT>enter) {
                    enter=nearT; enterNormal=float3(0); enterNormal[a]=direction[a]>0.0 ? -1.0 : 1.0;
                }
                leave=min(leave,farT);
            }
        }
        float t=max(minT,enter);
        if (t>=leave) return miss();
        float3 p=fma(float3(t),direction,origin);
        int3 cell=int3(floor(p)), stepDirection=int3(sign(direction));
        float3 nextT=float3(3.402823466e38), delta=float3(3.402823466e38);
        for (int a=0; a<3; a++) {
            if (direction[a]<0.0 && p[a]==floor(p[a])) cell[a]--;
            cell[a]=clamp(cell[a],minimum[a],maximum[a]-1);
            if (direction[a]!=0.0) {
                nextT[a]=(float(cell[a]+(direction[a]>0.0 ? 1 : 0))-origin[a])/direction[a];
                delta[a]=abs(1.0/direction[a]);
            }
        }
        float3 normal=enter<minT ? float3(0) : enterNormal;
        int limit=size.x+size.y+size.z+1;
        for (int iteration=0; iteration<limit; iteration++) {
            if (any((cell<minimum)) || any((cell>=maximum)) || t>=leave) break;
            if(scene.streamed()) {
                uint page=sectionPage(cell);
                if((page==0xffffffffu&&diagnostic.control.y!=3u)||(page!=0xffffffffu&&scene.word(page+3u)==1u)) {
                    // Two-level DDA: an absent/queued or explicit empty section costs one section step.
                    // Missing primary surfaces keep raster during composition; they are never opaque cubes.
                    float3 sectionMin=floor(float3(cell)/16.0)*16.0;
                    float3 sectionExit=float3(3.402823466e38);
                    for(int a=0;a<3;a++)if(direction[a]!=0.0)
                    sectionExit[a]=(sectionMin[a]+(direction[a]>0.0?16.0:0.0)-origin[a])/direction[a];
                    float jump=min(sectionExit.x,min(sectionExit.y,sectionExit.z));
                    if(jump>=leave)break;
                    t=max(t,jump);p=fma(float3(t),direction,origin);cell=int3(floor(p));normal=float3(0);
                    bool face=false;
                    for(int a=0;a<3;a++) {
                        if(direction[a]<0.0&&p[a]==floor(p[a]))cell[a]--;
                        if(sectionExit[a]==jump) {
                            // Snap exact crossed planes: fma may otherwise round back into the old section.
                            cell[a]=int(sectionMin[a])+(direction[a]>0.0?16:-1);
                            if(!face){normal[a]=-float(stepDirection[a]);face=true;}
                        }
                        nextT[a]=direction[a]==0.0?3.402823466e38:(float(cell[a]+(direction[a]>0.0?1:0))-origin[a])/direction[a];
                    }
                    continue;
                }
            }
            float boundary=min(nextT.x,min(nextT.y,nextT.z));
            uint material=cellValue(cell);
            bool live=(diagnostic.control.x&2u)!=0u;
            if (live) {
                uint occupancy=material&255u;
                if (diagnostic.control.y==3u) {
                    uint marker=material==0xffffffffu ? 4u : occupancy;
                    if (marker!=0u && t<min(boundary,leave)) return Hit(true,cell,marker,t,normal,float3(0),0u,0.0,1.0);
                } else if (material!=0xffffffffu && occupancy==1u && t<min(boundary,leave)) {
                    // A cube without a palette entry is a real occluder but has no material to shade.
                    uint flags=((material>>8)&255u)==0u ? FLAG_NO_MATERIAL : 0u;
                    return Hit(true,cell,material,t,normal,float3(0.65),flags,float((material>>24)&15u)/15.0,1.0);
                } else if (material!=0xffffffffu && occupancy==3u && textures.available()) {
                    Hit candidate=modelHit(origin,direction,cell,material,t,min(boundary,leave));
                    if (candidate.found) return candidate;
                }
            } else if (material!=0u && material!=0xffffffffu && t<min(boundary,leave))
            return Hit(true,cell,material,t,normal,float3(0),0u,0.0,1.0);
            if (boundary>=leave) break;
            bool3 crossed=(nextT==float3(boundary));
            for (int a=0; a<3; a++) if (crossed[a]) { cell[a]+=stepDirection[a]; nextT[a]+=delta[a]; }
            // An exact edge/corner crossing enters through several faces at once. Report a face whose
            // neighbor outside the entered cell is not an opaque cube, so a floor seen exactly at a cell
            // edge keeps its top face instead of a hidden side face.
            normal=float3(0); int fallback=-1; bool selected=false;
            for (int a=0; a<3; a++) if (crossed[a]) {
                if (fallback<0) fallback=a;
                int3 outside=cell; outside[a]-=stepDirection[a];
                uint neighbor=cellValue(outside);
                if (!selected && (neighbor==0xffffffffu || (neighbor&255u)!=1u)) { normal[a]=-float(stepDirection[a]); selected=true; }
            }
            if (!selected && fallback>=0) normal[fallback]=-float(stepDirection[fallback]);
            t=max(t,boundary);
        }
        return miss();
    }
    float3 materialColor(uint id) {
        if (id==1u) return float3(0.42,0.52,0.30);
        if (id==2u) return float3(0.63,0.32,0.18);
        if (id==3u) return float3(0.52,0.57,0.62);
        return float3(0.95,0.66,0.22);
    }
}
