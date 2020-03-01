package devoldefy;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.RewriteContext;
import org.cadixdev.mercury.SourceRewriter;

import java.util.Objects;

public class MyRemapper implements SourceRewriter {
    private final MappingSet mappings;
    
    public MyRemapper(MappingSet mappings) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
    }
    
    @Override
    public int getFlags() {
        return FLAG_RESOLVE_BINDINGS;
    }
    
    @Override
    public void rewrite(RewriteContext context) {
        context.getCompilationUnit().accept(new MyRemapperVisitor(context, this.mappings));
    }
    
}
