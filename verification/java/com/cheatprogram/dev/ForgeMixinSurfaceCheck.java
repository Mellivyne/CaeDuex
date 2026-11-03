package com.cheatprogram.dev;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Offline target/member validation against the actual mapped Forge Minecraft
 * JAR. It does not start a client or verify injector ordinals/control flow. */
public final class ForgeMixinSurfaceCheck {
    private final ZipFile mod,nativeJar;
    private final Map<String,ClassNode> classes=new HashMap<>();
    private int selectors,members,mixins,optional;
    private ForgeMixinSurfaceCheck(Path mod,Path nativeJar)throws Exception{this.mod=new ZipFile(mod.toFile());this.nativeJar=new ZipFile(nativeJar.toFile());}
    private ClassNode type(String name)throws Exception{
        if(classes.containsKey(name))return classes.get(name);
        var entry=nativeJar.getEntry(name+".class");
        if(entry==null)return null;
        var node=new ClassNode();new ClassReader(nativeJar.getInputStream(entry)).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);classes.put(name,node);return node;
    }
    private boolean method(String owner,String name,String descriptor,Set<String> visited)throws Exception{
        if(!visited.add(owner))return false;ClassNode t=type(owner);if(t==null)return false;
        for(MethodNode m:t.methods)if((name.equals("*")||m.name.equals(name))&&(descriptor==null||m.desc.equals(descriptor)))return true;
        if(name.equals("<init>"))return false;
        if(t.superName!=null&&method(t.superName,name,descriptor,visited))return true;
        for(String i:t.interfaces)if(method(i,name,descriptor,visited))return true;return false;
    }
    private boolean field(String owner,String name,String descriptor,Set<String> visited)throws Exception{
        if(!visited.add(owner))return false;ClassNode t=type(owner);if(t==null)return false;
        for(FieldNode f:t.fields)if(f.name.equals(name)&&(descriptor==null||f.desc.equals(descriptor)))return true;
        if(t.superName!=null&&field(t.superName,name,descriptor,visited))return true;
        for(String i:t.interfaces)if(field(i,name,descriptor,visited))return true;return false;
    }
    private static Object value(AnnotationNode a,String key){if(a.values!=null)for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(key))return a.values.get(i+1);return null;}
    private static List<?> list(Object value){return value==null?List.of():value instanceof List<?> l?l:List.of(value);}
    private static List<AnnotationNode> annotations(List<AnnotationNode> a,List<AnnotationNode> b){var out=new ArrayList<AnnotationNode>();if(a!=null)out.addAll(a);if(b!=null)out.addAll(b);return out;}
    private void at(Object object,String source)throws Exception{
        for(Object o:list(object))if(o instanceof AnnotationNode a){
            Object target=value(a,"target");
            if(target instanceof String s&&s.startsWith("L")&&s.contains(";")){
                int split=s.indexOf(';');String owner=s.substring(1,split),member=s.substring(split+1);
                if(type(owner)!=null){
                    int paren=member.indexOf('('),colon=member.indexOf(':');boolean ok;
                    if(paren>=0)ok=method(owner,member.substring(0,paren),member.substring(paren),new HashSet<>());
                    else if(colon>=0)ok=field(owner,member.substring(0,colon),member.substring(colon+1),new HashSet<>());
                    else continue;
                    if(!ok)throw new AssertionError(source+" missing injection member "+s);members++;
                }
            }
            if(a.values!=null)for(int i=1;i<a.values.size();i+=2)if(a.values.get(i) instanceof AnnotationNode||a.values.get(i) instanceof List<?>)at(a.values.get(i),source);
        }
    }
    private void check(String path)throws Exception{
        var entry=mod.getEntry(path);var node=new ClassNode();new ClassReader(mod.getInputStream(entry)).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        var targets=new ArrayList<String>();boolean pseudo=false;
        for(AnnotationNode a:annotations(node.visibleAnnotations,node.invisibleAnnotations))if(a.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;"))pseudo=true;
        for(AnnotationNode a:annotations(node.visibleAnnotations,node.invisibleAnnotations))if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")){
            for(Object v:list(value(a,"value")))targets.add(((Type)v).getInternalName());
            for(Object v:list(value(a,"targets")))targets.add(v.toString().replace('.','/'));
        }
        if(targets.isEmpty())return;mixins++;
        for(String target:targets)if(type(target)==null){if(pseudo){optional++;return;}throw new AssertionError(path+" missing mixin target "+target);}
        for(MethodNode m:node.methods)for(AnnotationNode a:annotations(m.visibleAnnotations,m.invisibleAnnotations)){
            String source=node.name+"."+m.name;
            Object methods=value(a,"method");
            for(Object raw:list(methods))if(raw instanceof String s){
                int paren=s.indexOf('(');String name=paren<0?s:s.substring(0,paren),desc=paren<0?null:s.substring(paren);boolean found=false;
                for(String target:targets)if(method(target,name,desc,new HashSet<>())){found=true;break;}
                if(!found)throw new AssertionError(source+" missing selector "+s+" in "+targets);selectors++;
            }
            at(value(a,"at"),source);at(value(a,"slice"),source);
        }
    }
    public static void main(String[] args)throws Exception{
        var check=new ForgeMixinSurfaceCheck(Path.of(args[0]),Path.of(args[1]));
        for(var e:Collections.list(check.mod.entries()))if(e.getName().startsWith("com/cheatprogram/mixin/")&&e.getName().endsWith(".class"))check.check(e.getName());
        System.out.println("FORGE_MIXIN_SURFACE_PASS mixins="+check.mixins+" selectors="+check.selectors+" nativeMembers="+check.members+" optionalAbsent="+check.optional+" (offline names/descriptors only)");
        check.mod.close();check.nativeJar.close();
    }
}
