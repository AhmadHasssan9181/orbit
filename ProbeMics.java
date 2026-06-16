import javax.sound.sampled.*;

public class ProbeMics {
    public static void main(String[] args) {
        System.out.println("--- AUDIO MIXER FORMATS ---");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            Line.Info[] targetInfos = mixer.getTargetLineInfo();
            if (targetInfos.length > 0) {
                System.out.println("NAME: " + info.getName());
                for (Line.Info lineInfo : targetInfos) {
                    if (lineInfo instanceof DataLine.Info) {
                        DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                        AudioFormat[] formats = dataLineInfo.getFormats();
                        for (AudioFormat f : formats) {
                            System.out.println("  FORMAT: " + f);
                        }
                    }
                }
            }
        }
    }
}
