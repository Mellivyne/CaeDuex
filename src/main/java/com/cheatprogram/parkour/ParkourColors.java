package com.cheatprogram.parkour;
/** Perceptual OKLab interpolation in linear light, then conversion to display sRGB. */
public final class ParkourColors {
    private static final int[] COLORS={0x5bcefa,0xf5a9b8,0xffffff,0xf5a9b8,0x5bcefa};
    private static final double[][] LAB=new double[5][];
    private static final int[] RAINBOW={0x12b8e4,0xb9dd42,0xf8ce36,0xf49a3e,0xef3554,0xee85bb,0x7960d5,0x12b8e4};
    private static final double[][] RAINBOW_LAB=new double[RAINBOW.length][];
    static{for(int i=0;i<5;i++)LAB[i]=lab(COLORS[i]);for(int i=0;i<RAINBOW.length;i++)RAINBOW_LAB[i]=lab(RAINBOW[i]);}
    private ParkourColors(){}
    public static int pride(double phase){phase-=Math.floor(phase);double p=phase*4;int i=Math.min(3,(int)p);double f=p-i;f=f*f*(3-2*f);double[] a=LAB[i],b=LAB[i+1];return rgb(a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f,a[2]+(b[2]-a[2])*f);}
    public static int rainbow(double phase){if(!Double.isFinite(phase))phase=0;phase-=Math.floor(phase);double p=phase*(RAINBOW_LAB.length-1);int i=Math.min(RAINBOW_LAB.length-2,(int)p);double f=p-i;f=f*f*(3-2*f);double[] a=RAINBOW_LAB[i],b=RAINBOW_LAB[i+1];return rgb(a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f,a[2]+(b[2]-a[2])*f);}
    private static double linear(int x){double v=x/255.0;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    private static double[] lab(int rgb){double r=linear((rgb>>16)&255),g=linear((rgb>>8)&255),b=linear(rgb&255);double l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b),m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b),s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b);return new double[]{.2104542553*l+.793617785*m-.0040720468*s,1.9779984951*l-2.428592205*m+.4505937099*s,.0259040371*l+.7827717662*m-.808675766*s};}
    private static int channel(double x){double v=x<=.0031308?12.92*x:1.055*Math.pow(x,1/2.4)-.055;return (int)Math.round(Math.max(0,Math.min(1,v))*255);}
    private static int rgb(double L,double a,double b){double l=Math.pow(L+.3963377774*a+.2158037573*b,3),m=Math.pow(L-.1055613458*a-.0638541728*b,3),s=Math.pow(L-.0894841775*a-1.291485548*b,3);return channel(4.0767416621*l-3.3077115913*m+.2309699292*s)<<16|channel(-1.2684380046*l+2.6097574011*m-.3413193965*s)<<8|channel(-.0041960863*l-.7034186147*m+1.707614701*s);}
}
