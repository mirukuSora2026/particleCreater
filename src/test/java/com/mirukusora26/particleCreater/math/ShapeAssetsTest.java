package com.mirukusora26.particleCreater.math;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShapeAssetsTest {
    @TempDir Path imports;
    @Test void pngBecomesBoundedPersistentPoints() throws Exception {
        BufferedImage image=new BufferedImage(4,4,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1,1,0xffffffff);
        ImageIO.write(image,"PNG",imports.resolve("dot.png").toFile());
        var shape=ShapeAssets.png("logo",imports,"dot.png",0.1);
        assertEquals("points",shape.kind);
        assertEquals(1,shape.points.size());
        assertThrows(IllegalArgumentException.class,()->ShapeAssets.png("bad",imports,"../dot.png",0.1));
    }
}
