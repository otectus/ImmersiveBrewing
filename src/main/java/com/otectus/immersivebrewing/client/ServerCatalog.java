package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.network.packet.CatalogSupportS2C;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** One source of recipe support for the Notebook, JEI and compatibility page. Unknown means not yet synced. */
public final class ServerCatalog {
    private static Map<ResourceLocation,PlanIndex.Entry> entries=Map.of();
    private static final Map<ResourceLocation,PlanIndex.Entry> staging=new LinkedHashMap<>();
    private static int generation=-1,next,loading=-1;
    private static boolean arrowConflict;
    public static Runnable changed=()->{};
    public static void accept(CatalogSupportS2C p) {
        if(p.page()==0) { staging.clear(); next=0; loading=p.generation(); }
        if(p.generation()!=loading||p.page()!=next++) { staging.clear(); loading=-1; return; }
        for(var e:p.entries()) staging.put(e.recipe(),e);
        if(p.page()+1==p.pages()) {
            entries=Map.copyOf(staging); generation=p.generation(); arrowConflict=p.arrowConflict(); changed.run();
        }
    }
    public static PlanIndex.Entry entry(ResourceLocation id) { return entries.get(id); }
    public static Collection<PlanIndex.Entry> entries() { return entries.values(); }
    public static int generation() { return generation; }
    public static boolean ready() { return generation>=0; }
    public static boolean arrowConflict() { return arrowConflict; }
    public static void reset() { entries=Map.of(); staging.clear(); generation=-1; loading=-1; next=0; }
    private ServerCatalog() {}
}
