import com.sun.tools.attach.VirtualMachine;
public class Attach {
  public static void main(String[] a) throws Exception {
    VirtualMachine vm = VirtualMachine.attach(a[0]);
    try { vm.loadAgent(a[1], a[2]); System.out.println("ATTACH_OK agent loaded"); }
    finally { vm.detach(); }
  }
}
