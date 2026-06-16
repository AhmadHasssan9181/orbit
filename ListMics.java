import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

public class ListMics {
    public static void main(String[] args) {
        System.out.println("--- AUDIO MIXERS ---");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            System.out.println("NAME: " + info.getName());
            System.out.println("DESC: " + info.getDescription());
            System.out.println("VENDOR: " + info.getVendor());
            System.out.println("--------------------");
        }
    }
}
