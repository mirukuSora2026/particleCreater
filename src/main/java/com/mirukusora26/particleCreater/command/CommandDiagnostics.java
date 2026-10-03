package com.mirukusora26.particleCreater.command;

import java.util.List;

/** Stable, English command context for user feedback and server logs. */
final class CommandDiagnostics {
    private CommandDiagnostics() {}

    static String path(List<String> args) {
        StringBuilder result=new StringBuilder("/pc");
        for(int i=0;i<Math.min(args.size(),5);i++) {
            String token=String.valueOf(args.get(i)).replaceAll("[\\p{Cntrl}§]","?");
            if(token.length()>48) token=token.substring(0,48)+"...";
            result.append(' ').append(token);
        }
        return result.toString();
    }

    static String failure(String category,List<String> args,Throwable cause) {
        String detail=cause.getMessage();
        if(detail==null||detail.isBlank()) detail=cause.getClass().getSimpleName();
        detail=detail.replaceAll("[\\p{Cntrl}§]","?");
        return "["+category+"] "+path(args)+" failed: "+detail;
    }

    static String inputCategory(List<String> args,Throwable cause) {
        String message=cause.getMessage()==null?"":cause.getMessage().toLowerCase(java.util.Locale.ROOT);
        if(message.contains("formula")||message.contains("expression")||message.contains("unknown variable")) return "FORMULA";
        if(message.contains("coordinate")||message.contains("world")||message.contains("saved location")) return "LOCATION";
        if(message.contains("particle data")||message.contains("data key")||message.contains("target entity")||message.contains(" data failed:")) return "PARTICLE_DATA";
        if(message.contains("layer")) return "LAYER";
        if(message.contains("shape")) return "SHAPE";
        if(message.contains("parameter")||message.contains("variable override")) return "VARIABLE";
        if(!args.isEmpty()) return switch(args.getFirst().toLowerCase(java.util.Locale.ROOT)) {
            case "shape" -> "SHAPE";
            case "layer" -> "LAYER";
            case "location" -> "LOCATION";
            case "motion" -> "MOTION";
            case "param" -> "VARIABLE";
            case "import","export" -> "FILE";
            case "play","spawn","preview" -> "PLAYBACK";
            default -> "INPUT";
        };
        return "INPUT";
    }
}
