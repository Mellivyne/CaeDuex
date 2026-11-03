package com.cheatprogram.compat;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Keep share codes/blueprints portable while adapting 26.3's native id/properties keys. */
public final class BlockStateNbt {
    private BlockStateNbt() { }
    public static CompoundTag nativeState(CompoundTag original){
        CompoundTag tag=original.copy();
        if(!tag.contains("id")&&tag.contains("Name")){tag.put("id",tag.get("Name").copy());tag.remove("Name");}
        if(!tag.contains("properties")&&tag.contains("Properties")){tag.put("properties",tag.get("Properties").copy());tag.remove("Properties");}
        return tag;
    }
    public static BlockState read(HolderGetter<Block> registry,CompoundTag state){return NbtUtils.readBlockState(registry,nativeState(state));}
    public static CompoundTag write(BlockState state){
        CompoundTag tag=NbtUtils.writeBlockState(state);
        if(tag.contains("id")){tag.put("Name",tag.get("id").copy());tag.remove("id");}
        if(tag.contains("properties")){tag.put("Properties",tag.get("properties").copy());tag.remove("properties");}
        return tag;
    }
    public static String particle(String specification) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int start=specification.indexOf('{');
        if(start<0)return specification;
        CompoundTag options=TagParser.parseCompoundFully(specification.substring(start));
        var state=options.getCompound("block_state");
        if(state.isEmpty()||!state.get().contains("Name")&&!state.get().contains("Properties"))return specification;
        options.put("block_state",nativeState(state.get()));
        return specification.substring(0,start)+options;
    }
}
