
var repositoryWatcherSocket = new WebSocket('ws://localhost:9000/ws/repositoryWatcher');

$('#subscribe').click(function() {
	var repository = $('#repo').val();
	var interval = $('#interval').val();
	var re = new RegExp("^[0-9]{1,3}$");

	if ( ! re.test(interval) ) {
		alert("The interval must be numeric and less than 999");
	} else if ( repository.length < 1 ) {
		alert("The repository can't be empty");
	} else {
		var jsMsg = { "action": "subscribe", "repository": repository, "interval": parseInt(interval) };
		repositoryWatcherSocket.send(JSON.stringify(jsMsg));
	};
});

$('#repo-stargazers-box').on('click', '.remove', function() {
	var repository = $(this).attr('data-repo');
	var jsMsg = { "action": "unsubscribe", "repository": repository };
	repositoryWatcherSocket.send(JSON.stringify(jsMsg));
});

repositoryWatcherSocket.onmessage = function(event) {
	var jsMsg = JSON.parse(event.data);
	console.log(event.data);

	if ( jsMsg.type == "refresh" ) {
		$("#repo-stargazers-box").html("<table></table>");
		$.each(jsMsg.counts, function(repo, stargazersCount) {
			$("#repo-stargazers-box table:last-child").append("<tr id='"+repo+"''><td class='repo'>"+repo+"</td><td class='stargazers-count'>"+stargazersCount+"</td><td class='action'><a data-repo='"+repo+"' class='remove' href=\"#\">Unsubscribe</a></td></tr>");
		});
		$("#repo-stargazers-box").animate({opacity:0},200,"linear",function(){
			$(this).animate({opacity:1},200);
		});
	}
};
