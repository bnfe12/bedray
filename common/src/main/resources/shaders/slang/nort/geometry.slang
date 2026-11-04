public module nort_geometry;
import "nort/types.slang";
import "nort/inputs.slang";

extension<S : INoRtScene, M : INoRtMaterials, R : INoRtRaster> NoRtRenderer<S,M,R> {
    Hit miss() { return Hit(false,int3(0),0u,0.0,float3(0),float3(0),0u,0.0,0.0); }
    float3 boundsMinimum() {
        if(scene.streamed()) {
            return float3(scene.boundsMin().xyz);
        } else {
            return float3(0);
        }
    }
    float sceneDistance() {
        if(scene.streamed()) {
            return length(float3(scene.dimensions().xyz));
        } else {
            return 160.0;
        }
    }
    bool diffuseBounceEnabled() {
        if(scene.streamed()) {
            return (scene.pageInfo().w&1u)!=0u;
        } else {
            return true;
        }
    }
    bool translucentOpticsEnabled() {
        if(scene.streamed()) {
            return (scene.pageInfo().w&2u)!=0u;
        } else {
            return true;
        }
    }
    uint sectionPage(int3 cell) {
        int3 key=int3(floor(float3(cell)/16.0))+scene.originSection().xyz;
        uint h=uint(key.x)*0x8da6b343u^uint(key.y)*0xd8163841u^uint(key.z)*0xcb1ab31fu;
        h^=h>>16;h*=0x7feb352du;h^=h>>15;
        uint index=h&(scene.pageInfo().x-1u);
        for(uint probe=0u;probe<scene.pageInfo().z;probe++,index=(index+1u)&(scene.pageInfo().x-1u)) {
            uint address=index*12u,state=scene.word(address+3u);
            if(state==0u)return 0xffffffffu;
            if(state!=3u&&all(int3(scene.word(address),scene.word(address+1u),scene.word(address+2u))==key))return address;
        }
        return 0xffffffffu;
    }
    uint cellIndex(int3 cell) {
        if(scene.streamed()) {
            uint page=sectionPage(cell);
            if(page==0xffffffffu||scene.word(page+3u)!=2u)return 0xffffffffu;
            int3 local=cell&int3(15);
            return scene.word(page+4u)+uint(local.x+16*(local.y+16*local.z));
        } else {
            int3 size=int3(scene.dimensions().xyz);
            if (any((cell<int3(0))) || any((cell>=size))) return 0xffffffffu;
            uint index=uint(cell.x+size.x*(cell.y+size.y*cell.z));
            if ((diagnostic.control.x&2u)!=0u) {
                int3 tile=cell/16, local=cell%16;
                index=uint(tile.x+3*(tile.y+3*tile.z))*4096u+uint(local.x+16*(local.y+16*local.z));
            }
            return index<scene.dimensions().w ? index : 0xffffffffu;
        }
    }
    uint cellValue(int3 cell) {
        if(scene.streamed()) {
            if(any((float3(cell)<boundsMinimum()))||any((float3(cell)>=boundsMinimum()+float3(scene.dimensions().xyz))))return 0xffffffffu;
            uint page=sectionPage(cell);
            if(page==0xffffffffu)return 0xffffffffu;
            if(scene.word(page+3u)==1u)return scene.word(page+8u);
            int3 local=cell&int3(15);
            uint index=scene.word(page+4u)+uint(local.x+16*(local.y+16*local.z));
            return index<uint(scene.wordCount())?scene.word(index):0xffffffffu;
        } else {
            uint index=cellIndex(cell);
            return index==0xffffffffu ? 0xffffffffu : scene.cell(index);
        }
    }
}
