package semp.cfg;

import com.fasterxml.jackson.core.JsonProcessingException;
import semp.cfg.model.HTTPMethod;
import semp.cfg.model.SEMPError;
import semp.cfg.model.SempResponse;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class RestCommandList {
    class Command {
        private HTTPMethod method;
        private String resourcePath;
        private String payload;

        Command(HTTPMethod method, String resourcePath, String payload) {
            this.method = method;
            this.resourcePath = resourcePath;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return String.format("%s %s%s%n", method.name(), resourcePath,
                    Objects.isNull(payload) || payload.isEmpty() ? "" : "\n" + payload);
        }
    }

    private List<Command> commands = new LinkedList<>();

    public void append(HTTPMethod method, String resourcePath, String payload) {
        commands.add(new Command(method, resourcePath, payload));
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        commands.forEach(sb::append);
        return sb.toString();
    }

    public void exectue(SempClient sempClient) {
        for (Iterator<Command> iterator = commands.iterator(); iterator.hasNext(); ) {
            var cmd = iterator.next();
            try {
                Utils.err("%s %s ", cmd.method.name(), cmd.resourcePath);
                var resp = SempResponse.ofJsonNode(sempClient.sendWithResourcePath(cmd.method.name(), cmd.resourcePath, cmd.payload));
                if (resp.getMeta().getResponseCode() == 200) {
                    Utils.err("OK%n");
                    iterator.remove();
                } else if (cmd.method == HTTPMethod.DELETE &&
                        resp.getMeta().getError().getCode() == SEMPError.NOT_ALLOWED.getValue()) {
                    Utils.err("%s, retry later%n", SEMPError.NOT_ALLOWED);
                } else {
                    Utils.err("%n%s%n", resp.getMeta().toString());
                    System.exit(1);
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        if (! commands.isEmpty()) exectue(sempClient);
    }
}