using System.Xml.Linq;
using AudioEarMute;
using Xunit;

namespace AudioEarMute.Tests;

public class AutostartTaskXmlTests
{
    private const string Exe = "D:\\Tools\\AudioEarMute\\dist\\AudioEarMute.exe";
    private const string User = "DESKTOP\\tester";

    private static XDocument Parse(string exe = Exe, string user = User) =>
        XDocument.Parse(Autostart.BuildTaskXml(exe, user));

    private static readonly XNamespace Ns = "http://schemas.microsoft.com/windows/2004/02/mit/task";

    [Fact]
    public void Produces_wellformed_xml()
    {
        var document = Parse();
        Assert.Equal(Ns + "Task", document.Root!.Name);
    }

    [Fact]
    public void Runs_the_given_executable()
    {
        var command = Parse().Descendants(Ns + "Command").Single().Value;
        Assert.Equal(Exe, command);
    }

    [Fact]
    public void Working_directory_is_the_executable_folder()
    {
        var workingDirectory = Parse().Descendants(Ns + "WorkingDirectory").Single().Value;
        Assert.Equal("D:\\Tools\\AudioEarMute\\dist", workingDirectory);
    }

    [Fact]
    public void Triggers_on_logon_of_the_given_user()
    {
        var trigger = Parse().Descendants(Ns + "LogonTrigger").Single();
        Assert.Equal("true", trigger.Element(Ns + "Enabled")!.Value);
        Assert.Equal(User, trigger.Element(Ns + "UserId")!.Value);
    }

    [Fact]
    public void Runs_without_elevation_so_no_admin_prompt_appears()
    {
        var principal = Parse().Descendants(Ns + "Principal").Single();
        Assert.Equal("LeastPrivilege", principal.Element(Ns + "RunLevel")!.Value);
        Assert.Equal("InteractiveToken", principal.Element(Ns + "LogonType")!.Value);
    }

    [Fact]
    public void Has_no_execution_time_limit()
    {
        // The default of three days would kill a tray app.
        Assert.Equal("PT0S", Parse().Descendants(Ns + "ExecutionTimeLimit").Single().Value);
    }

    [Fact]
    public void Survives_running_on_battery()
    {
        var settings = Parse().Descendants(Ns + "Settings").Single();
        Assert.Equal("false", settings.Element(Ns + "DisallowStartIfOnBatteries")!.Value);
        Assert.Equal("false", settings.Element(Ns + "StopIfGoingOnBatteries")!.Value);
    }

    [Fact]
    public void Second_logon_does_not_spawn_a_duplicate()
    {
        Assert.Equal("IgnoreNew", Parse().Descendants(Ns + "MultipleInstancesPolicy").Single().Value);
    }

    [Fact]
    public void Path_with_ampersand_stays_wellformed_and_decodes_back()
    {
        const string awkward = "D:\\Rock & Roll\\AudioEarMute.exe";

        var command = Parse(exe: awkward).Descendants(Ns + "Command").Single().Value;

        Assert.Equal(awkward, command);
    }

    [Fact]
    public void Path_with_angle_brackets_and_quotes_stays_wellformed()
    {
        const string awkward = "D:\\a\"b\\<c>\\AudioEarMute.exe";

        var command = Parse(exe: awkward).Descendants(Ns + "Command").Single().Value;

        Assert.Equal(awkward, command);
    }

    [Theory]
    [InlineData("a&b", "a&amp;b")]
    [InlineData("a<b>c", "a&lt;b&gt;c")]
    [InlineData("say \"hi\"", "say &quot;hi&quot;")]
    [InlineData("plain", "plain")]
    public void Escape_covers_the_xml_significant_characters(string raw, string expected)
    {
        Assert.Equal(expected, Autostart.Escape(raw));
    }

    [Fact]
    public void Ampersand_is_escaped_before_the_entities_it_introduces()
    {
        // Replacement order matters: & first, otherwise this would come out as &amp;lt;
        Assert.Equal("&amp;lt;", Autostart.Escape("&lt;"));
    }
}

public class TaskCommandExtractionTests
{
    [Fact]
    public void Reads_back_the_path_it_wrote()
    {
        var xml = Autostart.BuildTaskXml("D:\\Tools\\AudioEarMute.exe", "PC\\user");

        Assert.Equal("D:\\Tools\\AudioEarMute.exe", Autostart.ExtractCommand(xml));
    }

    [Fact]
    public void Round_trips_a_path_with_xml_significant_characters()
    {
        const string awkward = "D:\\Rock & Roll\\<v2>\\AudioEarMute.exe";
        var xml = Autostart.BuildTaskXml(awkward, "PC\\user");

        Assert.Equal(awkward, Autostart.ExtractCommand(xml));
    }

    [Fact]
    public void Round_trips_a_non_ascii_path()
    {
        // This case is why the task file is read instead of the output of schtasks,
        // which prints in the console code page. The literals stay non-ASCII on purpose.
        const string nonAscii = "D:\\Программы\\Naïve Ω\\AudioEarMute.exe";
        var xml = Autostart.BuildTaskXml(nonAscii, "PC\\пользователь");

        Assert.Equal(nonAscii, Autostart.ExtractCommand(xml));
    }

    [Fact]
    public void Returns_null_when_there_is_no_command_element()
    {
        Assert.Null(Autostart.ExtractCommand("<Task><Actions /></Task>"));
    }

    [Fact]
    public void Returns_null_for_an_empty_command()
    {
        Assert.Null(Autostart.ExtractCommand("<Task><Command>   </Command></Task>"));
    }

    [Fact]
    public void Returns_null_for_a_truncated_document()
    {
        Assert.Null(Autostart.ExtractCommand("<Task><Command>D:\\x.exe"));
    }
}
