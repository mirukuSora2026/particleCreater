package com.mirukusora26.particleCreater.command;

/** Numeric bounds shared by command options. */
final class CommandNumbers {
    private CommandNumbers() {}

    static int ticks(String value) {
        if(value.equalsIgnoreCase("infinite")) return -1;
        if(value.endsWith("s")) {
            double seconds=number(value.substring(0,value.length()-1));
            double result=seconds*20;
            if(seconds<0||!Double.isFinite(result)||result>72000.5)
                throw new IllegalArgumentException("Time must be 0..72000 ticks or infinite: "+value);
            long rounded=Math.round(result);
            if(rounded>72000) throw new IllegalArgumentException("Time must be 0..72000 ticks or infinite: "+value);
            return (int)rounded;
        }
        String digits=value.endsWith("t")?value.substring(0,value.length()-1):value;
        try {
            int result=Integer.parseInt(digits);
            if(result>=0&&result<=72000) return result;
        } catch(NumberFormatException ignored) {}
        throw new IllegalArgumentException("Time must be 0..72000 ticks or infinite: "+value);
    }

    static int pageOffset(int page,int total) {
        if(page<1||total<0) throw new IllegalArgumentException("Page out of range.");
        long offset=(long)(page-1)*20;
        if(offset>Integer.MAX_VALUE||(total==0&&page!=1)||(total>0&&offset>=total))
            throw new IllegalArgumentException("Page out of range.");
        return (int)offset;
    }

    private static double number(String value) {
        try {
            double result=Double.parseDouble(value);
            if(Double.isFinite(result)) return result;
        } catch(NumberFormatException ignored) {}
        throw new IllegalArgumentException("Expected a finite number: "+value);
    }
}
