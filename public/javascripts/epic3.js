
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

$('#repo-stargazers-box').on('click', '.unsubscribe', function() {
	var repository = $(this).attr('data-repository');
	var jsMsg = { "action": "unsubscribe", "repository": repository };
	repositoryWatcherSocket.send(JSON.stringify(jsMsg));
	$('tr[id="'+repository+'"]').remove();
});

repositoryWatcherSocket.onmessage = function(event) {
	var jsMsg = JSON.parse(event.data);
	console.log(event.data);

	if ( jsMsg.type == "refresh" ) {
		if ( $('tr[id="'+jsMsg.repository+'"]').length ) {
			$('tr[id="'+jsMsg.repository+'"] > td[class="stargazers-count"]').text(jsMsg.stars);
		} else {
			$('#repo-stargazers-box table:last-child').append(makeTR(jsMsg.repository, jsMsg.stars));
		}
		
		$('tr[id="'+jsMsg.repository+'"]').animate( {opacity: 0}, 200, 'linear', function() {
			$(this).animate( {opacity:1}, 200 );
		});
	}
};

function makeTR(repository, stars) {
	return  '<tr id="' + repository +
					'"><td class="repo">' + repository +
					'</td><td class="stargazers-count">' + stars + 
					'</td><td class="action"><a data-repository="' + repository + '" ' +
					'class="unsubscribe" href="#">Unsubscribe</a></td></tr>';
}
