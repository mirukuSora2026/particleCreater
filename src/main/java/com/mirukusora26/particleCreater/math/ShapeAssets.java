package com.mirukusora26.particleCreater.math;

import com.mirukusora26.particleCreater.model.EffectDefinition.Shape;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Rasterizes text or a local PNG into a persistent bounded point shape. */
public final class ShapeAssets {
    private ShapeAssets() {}
    public static Shape text(String id,String value,double pixelSize) {
        if(value==null||value.isBlank()||value.length()>64) throw new IllegalArgumentException("Text must contain 1..64 characters");
        Font font=new Font(Font.SANS_SERIF,Font.BOLD,48);
        BufferedImage probe=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure=probe.createGraphics();measure.setFont(font);FontMetrics metrics=measure.getFontMetrics();
        int width=Math.min(2048,metrics.stringWidth(value)+8),height=64;measure.dispose();
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setFont(font);graphics.setColor(Color.WHITE);graphics.drawString(value,4,metrics.getAscent()+4);graphics.dispose();
        return fromImage(id,image,pixelSize,96);
    }
    public static Shape png(String id,Path importFolder,String fileName,double pixelSize) throws IOException {
        if(!fileName.matches("[a-zA-Z0-9_-]{1,64}\\.png")) throw new IllegalArgumentException("PNG name may contain only letters, digits, _ and -");
        Path path=importFolder.resolve(fileName);
        if(!Files.isRegularFile(path)||Files.size(path)>4_194_304) throw new IllegalArgumentException("PNG must exist in the plugin imports folder and be at most 4 MiB");
        BufferedImage image=ImageIO.read(path.toFile());
        if(image==null||image.getWidth()>512||image.getHeight()>512) throw new IllegalArgumentException("PNG must be at most 512x512 pixels");
        return fromImage(id,image,pixelSize,96);
    }
    private static Shape fromImage(String id,BufferedImage image,double pixelSize,int alphaThreshold) {
        if(!Double.isFinite(pixelSize)||pixelSize<0.01||pixelSize>2) throw new IllegalArgumentException("Pixel size must be 0.01..2 blocks");
        Shape shape=new Shape(id,"points");
        int stride=Math.max(1,(int)Math.ceil(Math.sqrt((double)image.getWidth()*image.getHeight()/1800)));
        for(int y=0;y<image.getHeight();y+=stride)for(int x=0;x<image.getWidth();x+=stride) {
            if((image.getRGB(x,y)>>>24)>=alphaThreshold) shape.points.add(java.util.List.of((x-image.getWidth()/2.0)*pixelSize,(image.getHeight()/2.0-y)*pixelSize,0.0));
        }
        if(shape.points.isEmpty()) throw new IllegalArgumentException("Image has no visible pixels");
        if(shape.points.size()>2048) throw new IllegalArgumentException("Image has too many points; use a smaller or sparser image");
        return shape;
    }
}
